package node

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/skip2/go-qrcode"
)

type SetupState struct {
	Permissions map[string]bool `json:"permissions"`
	Version     string          `json:"version"`
	Name        string          `json:"name"`
	OS          string          `json:"os"`
	Arch        string          `json:"arch"`
	FreeGB      int64           `json:"freeGB"`
	Workspace   string          `json:"workspace"`
	Addresses   []Address       `json:"addresses"`
	Invitation  Invitation      `json:"invitation"`
	URI         string          `json:"uri"`
	QR          string          `json:"qr"`
	Attempts    int             `json:"attempts"`
	Pending     *Pending        `json:"pending"`
	Phones      []Phone         `json:"phones"`
	Screen      screenStatus    `json:"screen"`
	Config      Config          `json:"config"`
	LastSeen    int64           `json:"lastSeenMs"`
	Background  bool            `json:"background"`
	ServicePath string          `json:"servicePath"`
	Backends    []string        `json:"backends"`
}

func (s *Server) State() SetupState {
	cfg := s.Home.Settings()
	addresses := Addresses(s.Port())
	inv, pending, attempts := s.Pairing.Snapshot(s.Home, addresses)
	png, _ := qrcode.Encode(inv.URI(), qrcode.Medium, 440)
	phones, _ := s.Home.Phones()
	var st syscall.Statfs_t
	syscall.Statfs(s.Home.Dir, &st)
	s.mu.Lock()
	last := s.lastSeen
	s.mu.Unlock()
	backends := []string{}
	if runtime.GOOS == "darwin" {
		backends = append(backends, "native")
	} else {
		for _, name := range []string{"x11vnc", "wayvnc"} {
			if _, e := exec.LookPath(name); e == nil {
				backends = append(backends, name)
			}
		}
	}
	path := ServicePath()
	_, err := os.Stat(path)
	return SetupState{Permissions: permissions(), Version: Version, Name: cfg.Name, OS: runtime.GOOS, Arch: runtime.GOARCH, FreeGB: int64(st.Bavail) * int64(st.Bsize) / 1024 / 1024 / 1024, Workspace: filepath.Join(s.Home.Dir, "jobs"), Addresses: addresses, Invitation: inv, URI: inv.URI(), QR: "data:image/png;base64," + base64.StdEncoding.EncodeToString(png), Attempts: attempts, Pending: pending, Phones: phones, Screen: screenInfo(cfg), Config: cfg, LastSeen: last, Background: err == nil, ServicePath: path, Backends: backends}
}
func (s *Server) Control() error {
	path := filepath.Join(s.Home.Dir, "control.sock")
	os.Remove(path)
	l, e := net.Listen("unix", path)
	if e != nil {
		return e
	}
	os.Chmod(path, 0600)
	mux := http.NewServeMux()
	mux.HandleFunc("/state", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "GET" {
			w.WriteHeader(405)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(s.State())
	})
	mux.HandleFunc("/action", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "POST" {
			w.WriteHeader(405)
			return
		}
		var a Frame
		if json.NewDecoder(io.LimitReader(r.Body, 16384)).Decode(&a) != nil {
			http.Error(w, "Invalid request", 400)
			return
		}
		if e := s.Action(a); e != nil {
			http.Error(w, e.Error(), 400)
			return
		}
		json.NewEncoder(w).Encode(s.State())
	})
	server := &http.Server{Handler: mux, ReadHeaderTimeout: 5 * time.Second}
	go server.Serve(l)
	go func() { <-s.stop; server.Close(); os.Remove(path) }()
	return nil
}
func (s *Server) Action(a Frame) error {
	switch a.Str("action") {
	case "pair-open":
		s.Pairing.Open()
		return nil
	case "pair-decision":
		return s.Pairing.Decide(a.Str("id"), a.Bool("allow"))
	case "screen":
		c := s.Home.Settings()
		backend := a.Str("backend")
		if backend != "" && backend != "native" && backend != "x11vnc" && backend != "wayvnc" {
			return errors.New("unknown screen backend")
		}
		if runtime.GOOS != "darwin" && backend == "native" {
			return errors.New("native capture is macOS only")
		}
		c.Screen = backend
		return s.Home.SaveConfig(c)
	case "settings":
		c := s.Home.Settings()
		c.AgentScreen = a.Bool("agentScreen")
		c.KeepAwake = a.Bool("keepAwake")
		if c.KeepAwake {
			if _, err := exec.LookPath(awakeCommand().Path); err != nil {
				return errors.New("keep-awake backend is not installed on this computer")
			}
		}
		return s.Home.SaveConfig(c)
	case "background":
		return InstallService(s.Home.Dir, a.Bool("enabled"))
	case "permission":
		if runtime.GOOS != "darwin" {
			return errors.New("open your desktop's screen sharing settings")
		}
		pane := "Privacy_ScreenCapture"
		requestPermission(a.Str("kind") == "accessibility")
		if a.Str("kind") == "accessibility" {
			pane = "Privacy_Accessibility"
		}
		return exec.Command("open", "x-apple.systempreferences:com.apple.preference.security?"+pane).Run()
	case "stop":
		go func() { time.Sleep(200 * time.Millisecond); s.Close() }()
		return nil
	default:
		return errors.New("unknown setup action")
	}
}
func Control(home, path, method string, body io.Reader, out any) error {
	client := &http.Client{Timeout: 20 * time.Second, Transport: &http.Transport{DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, "unix", filepath.Join(home, "control.sock"))
	}}}
	req, e := http.NewRequest(method, "http://node"+path, body)
	if e != nil {
		return e
	}
	resp, e := client.Do(req)
	if e != nil {
		return e
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 8192))
		return errors.New(strings.TrimSpace(string(b)))
	}
	return json.NewDecoder(resp.Body).Decode(out)
}
func EnsureRunning(home string) (SetupState, error) {
	var state SetupState
	if Control(home, "/state", "GET", nil, &state) == nil {
		return state, nil
	}
	exe, e := stableExecutable(home)
	if e != nil {
		return state, e
	}
	if e = os.MkdirAll(home, 0700); e != nil {
		return state, e
	}
	log, e := os.OpenFile(filepath.Join(home, "runner.log"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0600)
	if e != nil {
		return state, e
	}
	defer log.Close()
	cmd := exec.Command(exe, "serve", "--home", home)
	cmd.Stdout = log
	cmd.Stderr = log
	cmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
	if e = cmd.Start(); e != nil {
		return state, e
	}
	cmd.Process.Release()
	for i := 0; i < 50; i++ {
		if Control(home, "/state", "GET", nil, &state) == nil {
			return state, nil
		}
		time.Sleep(100 * time.Millisecond)
	}
	return state, errors.New("node did not start; see " + filepath.Join(home, "runner.log"))
}
func ServicePath() string {
	home, _ := os.UserHomeDir()
	if runtime.GOOS == "darwin" {
		return filepath.Join(home, "Library/LaunchAgents/app.umbra.node.plist")
	}
	return filepath.Join(home, ".config/systemd/user/umbra-node.service")
}
func xml(s string) string {
	r := strings.NewReplacer("&", "&amp;", "<", "&lt;", ">", "&gt;", "\"", "&quot;")
	return r.Replace(s)
}
func InstallService(home string, enabled bool) error {
	path := ServicePath()
	exe, e := stableExecutable(home)
	if e != nil {
		return e
	}
	if enabled {
		if e = os.MkdirAll(filepath.Dir(path), 0700); e != nil {
			return e
		}
		var text string
		if runtime.GOOS == "darwin" {
			text = `<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd"><plist version="1.0"><dict><key>Label</key><string>app.umbra.node</string><key>ProgramArguments</key><array><string>` + xml(exe) + `</string><string>serve</string><string>--home</string><string>` + xml(home) + `</string></array><key>RunAtLoad</key><true/><key>KeepAlive</key><true/><key>StandardOutPath</key><string>` + xml(filepath.Join(home, "service.log")) + `</string><key>StandardErrorPath</key><string>` + xml(filepath.Join(home, "service.log")) + `</string></dict></plist>`
		} else {
			text = "[Unit]\nDescription=Umbra remote node\nAfter=network-online.target\n[Service]\nExecStart=" + strconv.Quote(exe) + " serve --home " + strconv.Quote(home) + "\nRestart=on-failure\nRestartSec=5\n[Install]\nWantedBy=default.target\n"
		}
		if e = atomicWrite(path, []byte(text)); e != nil {
			return e
		}
		if runtime.GOOS == "darwin" { // Load at the next login; the already-running node remains the active process.
			return nil
		}
		out, e := exec.Command("systemctl", "--user", "daemon-reload").CombinedOutput()
		if e != nil {
			return fmt.Errorf("systemd reload: %s", out)
		}
		out, e = exec.Command("systemctl", "--user", "enable", "umbra-node.service").CombinedOutput()
		if e != nil {
			return fmt.Errorf("systemd enable: %s", out)
		}
		return nil
	}
	if runtime.GOOS == "darwin" {
		exec.Command("launchctl", "bootout", "gui/"+strconv.Itoa(os.Getuid()), path).Run()
	} else {
		exec.Command("systemctl", "--user", "disable", "--now", "umbra-node.service").Run()
	}
	if e = os.Remove(path); e != nil && !os.IsNotExist(e) {
		return e
	}
	return nil
}
