package node

import (
	"bufio"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"errors"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

type Frame map[string]any

func (f Frame) Str(k string) string { s, _ := f[k].(string); return s }
func (f Frame) Int(k string) int64  { v, _ := f[k].(float64); return int64(v) }
func (f Frame) Bool(k string) bool  { v, _ := f[k].(bool); return v }
func decode(v any, out any) error {
	b, e := json.Marshal(v)
	if e != nil {
		return e
	}
	return json.Unmarshal(b, out)
}

type Server struct {
	Home        *Home
	Pairing     Pairing
	Jobs        *Jobs
	epoch       string
	listener    net.Listener
	lock        *os.File
	stop        chan struct{}
	mu          sync.Mutex
	connections map[net.Conn]bool
	lastPhone   string
	lastSeen    int64
}

func Start(h *Home, port int, pair bool) (*Server, error) {
	lock, e := os.OpenFile(filepath.Join(h.Dir, "runner.lock"), os.O_CREATE|os.O_RDWR, 0600)
	if e != nil {
		return nil, e
	}
	if e = syscall.Flock(int(lock.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); e != nil {
		lock.Close()
		return nil, errors.New("node is already running; use setup to manage it")
	}
	s := &Server{Home: h, epoch: strconv.FormatInt(time.Now().UnixNano(), 36), lock: lock, stop: make(chan struct{}), connections: map[net.Conn]bool{}}
	if pair {
		s.Pairing.Open()
	}
	s.Jobs = NewJobs(filepath.Join(h.Dir, "jobs"), s.epoch)
	config := &tls.Config{Certificates: []tls.Certificate{h.Cert}, MinVersion: tls.VersionTLS13, MaxVersion: tls.VersionTLS13, ClientAuth: tls.RequireAnyClientCert, VerifyPeerCertificate: func(raw [][]byte, _ [][]*x509.Certificate) error {
		if len(raw) != 1 {
			return errors.New("one client certificate is required")
		}
		if h.Paired(fingerprint(raw[0])) || s.Pairing.IsOpen() {
			return nil
		}
		return errors.New("phone is not paired")
	}}
	s.listener, e = tls.Listen("tcp", net.JoinHostPort("", strconv.Itoa(port)), config)
	if e != nil {
		s.Close()
		return nil, e
	}
	go func() {
		for {
			c, e := s.listener.Accept()
			if e != nil {
				return
			}
			s.mu.Lock()
			s.connections[c] = true
			s.mu.Unlock()
			go s.serve(c.(*tls.Conn))
		}
	}()
	go func() {
		t := time.NewTicker(time.Second)
		defer t.Stop()
		for {
			select {
			case <-s.stop:
				return
			case <-t.C:
				s.Jobs.Expire()
			}
		}
	}()
	go s.keepAwake()
	return s, nil
}
func (s *Server) Port() int             { return s.listener.Addr().(*net.TCPAddr).Port }
func (s *Server) Done() <-chan struct{} { return s.stop }
func (s *Server) Close() {
	select {
	case <-s.stop:
		return
	default:
		close(s.stop)
	}
	if s.listener != nil {
		s.listener.Close()
	}
	s.mu.Lock()
	for c := range s.connections {
		c.Close()
	}
	s.mu.Unlock()
	if s.lock != nil {
		syscall.Flock(int(s.lock.Fd()), syscall.LOCK_UN)
		s.lock.Close()
	}
}
func (s *Server) serve(c *tls.Conn) {
	defer func() { c.Close(); s.mu.Lock(); delete(s.connections, c); s.mu.Unlock() }()
	c.SetDeadline(time.Now().Add(10 * time.Second))
	if c.Handshake() != nil {
		return
	}
	c.SetDeadline(time.Time{})
	peer := fingerprint(c.ConnectionState().PeerCertificates[0].Raw)
	reader := bufio.NewReaderSize(c, 32*1024)
	for {
		line, e := readLine(reader, 1024*1024)
		if e != nil {
			return
		}
		var req Frame
		if json.Unmarshal(line, &req) != nil {
			return
		}
		if _, ok := req["id"]; !ok {
			return
		}
		var reply Frame
		if req.Str("op") == "pair" {
			name := req.Str("name")
			if len(name) > 120 {
				name = name[:120]
			}
			proof, err := s.Pairing.Pair(s.Home, peer, name, req.Str("proof"))
			if err != nil {
				reply = Frame{"ok": false, "error": err.Error()}
			} else {
				cfg := s.Home.Settings()
				reply = Frame{"ok": true, "proof": proof, "node_id": cfg.NodeID, "node_name": cfg.Name}
			}
		} else if !s.Home.Paired(peer) {
			reply = Frame{"ok": false, "error": "phone is no longer paired"}
		} else {
			s.mu.Lock()
			s.lastPhone = peer
			s.lastSeen = time.Now().UnixMilli()
			s.mu.Unlock()
			if req.Str("op") == "vnc" {
				req["_peer"] = peer
				if e = s.screen(c, reader, req); e != nil {
					json.NewEncoder(c).Encode(Frame{"re": req["id"], "ok": false, "error": e.Error()})
				}
				return
			}
			reply, e = s.handle(peer, req)
			if e != nil {
				reply = Frame{"ok": false, "error": e.Error()}
			}
		}
		reply["re"] = req["id"]
		if json.NewEncoder(c).Encode(reply) != nil {
			return
		}
		if req.Str("op") == "revoke" {
			return
		}
	}
}
func readLine(r *bufio.Reader, maxSize int) ([]byte, error) {
	var line []byte
	for {
		part, e := r.ReadSlice('\n')
		if len(line)+len(part) > maxSize {
			return nil, errors.New("frame too large")
		}
		line = append(line, part...)
		if e == nil {
			return line, nil
		}
		if e != bufio.ErrBufferFull {
			return nil, e
		}
	}
}
func (s *Server) handle(peer string, r Frame) (Frame, error) {
	ok := Frame{"ok": true}
	id := r.Str("job")
	switch r.Str("op") {
	case "hello":
		ok["info"] = s.Info()
	case "manifest":
		var m Manifest
		if e := decode(r["manifest"], &m); e != nil {
			return nil, e
		}
		return ok, s.Jobs.Manifest(id, peer, m)
	case "put":
		return ok, s.Jobs.Put(id, peer, r.Str("path"), r.Int("offset"), r.Str("data"), r.Bool("last"))
	case "run":
		var argv []string
		if e := decode(r["argv"], &argv); e != nil {
			return nil, e
		}
		v, e := s.Jobs.Run(id, peer, argv, r.Str("cwd"), r.Int("lease_ms"))
		ok["status"] = v
		return ok, e
	case "renew":
		v, e := s.Jobs.Renew(id, peer, r.Int("lease_ms"))
		ok["status"] = v
		return ok, e
	case "cancel":
		v, e := s.Jobs.End(id, peer, "CANCELLED")
		ok["status"] = v
		return ok, e
	case "jobs":
		ok["jobs"] = s.Jobs.List(peer)
	case "forget":
		return ok, s.Jobs.Forget(id, peer)
	case "fetch":
		data, e := s.Jobs.Fetch(id, peer, r.Str("path"))
		ok["data"] = data
		return ok, e
	case "revoke":
		ok["jobs"] = s.Jobs.List(peer)
		return ok, s.Home.SetPhone(Phone{Fingerprint: peer}, false)
	case "anchor":
		b, e := json.Marshal(Frame{"phone": peer, "received_ms": time.Now().UnixMilli(), "anchor": r["anchor"]})
		if e != nil {
			return nil, e
		}
		s.mu.Lock()
		defer s.mu.Unlock()
		f, e := os.OpenFile(filepath.Join(s.Home.Dir, "anchors.jsonl"), os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0600)
		if e != nil {
			return nil, e
		}
		defer f.Close()
		_, e = f.Write(append(b, '\n'))
		return ok, e
	case "anchors":
		list := []Frame{}
		s.mu.Lock()
		defer s.mu.Unlock()
		f, e := os.Open(filepath.Join(s.Home.Dir, "anchors.jsonl"))
		if os.IsNotExist(e) {
			ok["anchors"] = list
			return ok, nil
		}
		if e != nil {
			return nil, e
		}
		defer f.Close()
		scan := bufio.NewScanner(f)
		for scan.Scan() {
			var v Frame
			if json.Unmarshal(scan.Bytes(), &v) == nil && v.Str("phone") == peer {
				list = append(list, v)
			}
		}
		ok["anchors"] = list
		return ok, scan.Err()
	default:
		return nil, errors.New("unknown operation")
	}
	return ok, nil
}
func (s *Server) Info() Frame {
	cfg := s.Home.Settings()
	tools := map[string]string{}
	for _, name := range []string{"sh", "git", "python3", "node", "go", "cargo", "docker", "x11vnc", "wayvnc", "grdctl"} {
		if path, e := exec.LookPath(name); e == nil {
			tools[name] = path
		}
	}
	var st syscall.Statfs_t
	syscall.Statfs(s.Home.Dir, &st)
	return Frame{"nodeId": cfg.NodeID, "name": cfg.Name, "os": runtime.GOOS, "arch": runtime.GOARCH, "cpus": runtime.NumCPU(), "memoryMb": memoryMB(), "tools": tools, "epoch": s.epoch, "clockMs": time.Now().UnixMilli(), "workspace": filepath.Join(s.Home.Dir, "jobs"), "maxManifestBytes": MaxUpload, "protocol": 1, "screen": screenInfo(cfg), "addresses": Addresses(s.Port())}
}
func Addresses(port int) []Address {
	out := []Address{}
	interfaces, _ := net.Interfaces()
	for _, iface := range interfaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, _ := iface.Addrs()
		for _, a := range addrs {
			ip, _, e := net.ParseCIDR(a.String())
			if e != nil || ip.IsLinkLocalUnicast() || ip.IsLoopback() {
				continue
			}
			kind := "lan"
			if strings.HasPrefix(iface.Name, "tailscale") || ip.To4() != nil && ip[12] == 100 && ip[13] >= 64 && ip[13] <= 127 {
				kind = "tailscale"
			} else if strings.HasPrefix(iface.Name, "utun") || strings.HasPrefix(iface.Name, "wg") || strings.HasPrefix(iface.Name, "tun") {
				kind = "vpn"
			}
			out = append(out, Address{ip.String(), port, kind})
			if len(out) == 8 {
				return out
			}
		}
	}
	if len(out) == 0 {
		out = append(out, Address{"127.0.0.1", port, "local"})
	}
	return out
}
func memoryMB() int64 {
	if runtime.GOOS == "darwin" {
		out, e := exec.Command("sysctl", "-n", "hw.memsize").Output()
		if e == nil {
			v, _ := strconv.ParseInt(strings.TrimSpace(string(out)), 10, 64)
			return v / 1024 / 1024
		}
	}
	b, _ := os.ReadFile("/proc/meminfo")
	for _, line := range strings.Split(string(b), "\n") {
		if strings.HasPrefix(line, "MemTotal:") {
			fields := strings.Fields(line)
			if len(fields) > 1 {
				v, _ := strconv.ParseInt(fields[1], 10, 64)
				return v / 1024
			}
		}
	}
	return 0
}
