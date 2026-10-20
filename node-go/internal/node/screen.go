package node

import (
	"bufio"
	"encoding/binary"
	"encoding/json"
	"errors"
	"io"
	"net"
	"os/exec"
	"strconv"
	"time"
)

type screenStatus struct {
	Available bool   `json:"available"`
	Backend   string `json:"backend"`
	Detail    string `json:"detail"`
}

func screenInfo(c Config) screenStatus {
	if c.Screen == "native" {
		ready := nativeReady()
		return screenStatus{ready, "ScreenCaptureKit", nativeDetail()}
	}
	if c.Screen == "x11vnc" || c.Screen == "wayvnc" {
		_, e := exec.LookPath(c.Screen)
		return screenStatus{e == nil, c.Screen, "Loopback VNC through pinned mTLS"}
	}
	return screenStatus{false, "", "Jobs only · screen access skipped"}
}
func (s *Server) screen(conn net.Conn, reader *bufio.Reader, r Frame) error {
	cfg := s.Home.Settings()
	if r.Bool("agent") && !cfg.AgentScreen {
		return errors.New("agent screen access is disabled on this computer")
	}
	status := screenInfo(cfg)
	if !status.Available {
		return errors.New(status.Detail)
	}
	done := make(chan struct{})
	defer close(done)
	go func() {
		tick := time.NewTicker(time.Second)
		defer tick.Stop()
		for {
			select {
			case <-done:
				return
			case <-tick.C:
				c := s.Home.Settings()
				if !s.Home.Paired(r.Str("_peer")) || c.Screen != cfg.Screen || r.Bool("agent") && !c.AgentScreen {
					conn.Close()
					return
				}
			}
		}
	}()
	if cfg.Screen == "native" {
		if e := json.NewEncoder(conn).Encode(Frame{"ok": true, "re": r["id"]}); e != nil {
			return e
		}
		serveRFB(conn, reader, cfg.Name)
		return nil
	}
	// A private per-connection listener is created by the selected backend, always loopback-only.
	listener, e := net.Listen("tcp", "127.0.0.1:0")
	if e != nil {
		return e
	}
	port := listener.Addr().(*net.TCPAddr).Port
	listener.Close()
	var cmd *exec.Cmd
	if cfg.Screen == "x11vnc" {
		cmd = exec.Command("x11vnc", "-localhost", "-rfbport", strconv.Itoa(port), "-once", "-shared", "-nopw", "-quiet")
	} else {
		cmd = exec.Command("wayvnc", "127.0.0.1", strconv.Itoa(port))
	}
	if e = cmd.Start(); e != nil {
		return e
	}
	defer func() { cmd.Process.Kill(); cmd.Wait() }()
	var backend net.Conn
	for i := 0; i < 30; i++ {
		backend, e = net.DialTimeout("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)), 200*time.Millisecond)
		if e == nil {
			break
		}
		time.Sleep(100 * time.Millisecond)
	}
	if e != nil {
		return e
	}
	defer backend.Close()
	if e := json.NewEncoder(conn).Encode(Frame{"ok": true, "re": r["id"]}); e != nil {
		return e
	}
	copied := make(chan struct{})
	go func() { io.Copy(backend, reader); backend.Close(); close(copied) }()
	_, e = io.Copy(conn, backend)
	conn.Close()
	<-copied
	return nil
}
func serveRFB(conn net.Conn, in io.Reader, name string) error {
	conn.SetDeadline(time.Now().Add(15 * time.Second))
	defer conn.SetDeadline(time.Time{})
	if _, e := io.WriteString(conn, "RFB 003.008\n"); e != nil {
		return e
	}
	version := make([]byte, 12)
	if _, e := io.ReadFull(in, version); e != nil {
		return e
	}
	if string(version) != "RFB 003.008\n" {
		return errors.New("RFB 3.8 required")
	}
	conn.Write([]byte{1, 1})
	one := make([]byte, 1)
	if _, e := io.ReadFull(in, one); e != nil || one[0] != 1 {
		return errors.New("RFB security negotiation failed")
	}
	conn.Write([]byte{0, 0, 0, 0})
	if _, e := io.ReadFull(in, one); e != nil {
		return e
	}
	img, e := nativeCapture()
	if e != nil {
		return e
	}
	w, h := img.Bounds().Dx(), img.Bounds().Dy()
	if w < 1 || h < 1 || w > 8192 || h > 8192 {
		return errors.New("invalid screen dimensions")
	}
	header := []byte{byte(w >> 8), byte(w), byte(h >> 8), byte(h), 32, 24, 0, 1, 0, 255, 0, 255, 0, 255, 16, 8, 0, 0, 0, 0}
	conn.Write(header)
	binary.Write(conn, binary.BigEndian, uint32(len(name)))
	io.WriteString(conn, name)
	conn.SetDeadline(time.Time{})
	for {
		conn.SetReadDeadline(time.Now().Add(30 * time.Second))
		if _, e = io.ReadFull(in, one); e != nil {
			return e
		}
		switch one[0] {
		case 0:
			format := make([]byte, 19)
			if _, e = io.ReadFull(in, format); e != nil {
				return e
			}
			if format[3] != 32 || format[5] != 0 || format[6] != 1 || format[13] != 16 || format[14] != 8 || format[15] != 0 {
				return errors.New("only 32-bit little-endian truecolour is supported")
			}
		case 2:
			head := make([]byte, 3)
			if _, e = io.ReadFull(in, head); e != nil {
				return e
			}
			n := int(binary.BigEndian.Uint16(head[1:]))
			if n > 256 {
				return errors.New("too many encodings")
			}
			if _, e = io.CopyN(io.Discard, in, int64(n*4)); e != nil {
				return e
			}
		case 3:
			request := make([]byte, 9)
			if _, e = io.ReadFull(in, request); e != nil {
				return e
			}
			img, e = nativeCapture()
			if e != nil {
				return e
			}
			if img.Bounds().Dx() != w || img.Bounds().Dy() != h {
				return errors.New("display changed; reconnect")
			}
			frame := make([]byte, 16+w*h*4)
			frame[3] = 1
			binary.BigEndian.PutUint16(frame[8:], uint16(w))
			binary.BigEndian.PutUint16(frame[10:], uint16(h))
			for y := 0; y < h; y++ {
				for x := 0; x < w; x++ {
					red, green, blue, _ := img.At(x, y).RGBA()
					i := 16 + (y*w+x)*4
					frame[i] = byte(blue >> 8)
					frame[i+1] = byte(green >> 8)
					frame[i+2] = byte(red >> 8)
				}
			}
			conn.SetWriteDeadline(time.Now().Add(15 * time.Second))
			if _, e = conn.Write(frame); e != nil {
				return e
			}
			time.Sleep(100 * time.Millisecond)
		case 4:
			key := make([]byte, 7)
			if _, e = io.ReadFull(in, key); e != nil {
				return e
			}
			nativeKey(binary.BigEndian.Uint32(key[3:]), key[0] != 0)
		case 5:
			pointer := make([]byte, 5)
			if _, e = io.ReadFull(in, pointer); e != nil {
				return e
			}
			nativePointer(int(binary.BigEndian.Uint16(pointer[1:])), int(binary.BigEndian.Uint16(pointer[3:])), pointer[0], w, h)
		case 6:
			head := make([]byte, 7)
			if _, e = io.ReadFull(in, head); e != nil {
				return e
			}
			n := binary.BigEndian.Uint32(head[3:])
			if n > 1024*1024 {
				return errors.New("clipboard too large")
			}
			if _, e = io.CopyN(io.Discard, in, int64(n)); e != nil {
				return e
			}
		default:
			return errors.New("unsupported RFB message")
		}
	}
}
