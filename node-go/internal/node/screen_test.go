package node

import (
	"bufio"
	"context"
	"io"
	"net"
	"os"
	"os/exec"
	"runtime"
	"strings"
	"testing"
	"time"
)

func TestScreenBackendIsPrivateToTheHostUser(t *testing.T) {
	dir, err := os.MkdirTemp("", "un-")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(dir)
	info, err := os.Stat(dir)
	if err != nil || info.Mode().Perm() != 0700 {
		t.Fatal("screen directory must be private", err)
	}
	for _, name := range []string{"x11vnc", "wayvnc"} {
		cmd, address, err := screenBackend(name, dir)
		if err != nil {
			t.Fatal(err)
		}
		if !strings.HasPrefix(address, dir+string(os.PathSeparator)) {
			t.Fatal("socket escaped private directory")
		}
		args := strings.Join(cmd.Args, " ")
		if strings.Contains(args, "127.0.0.1") || strings.Contains(args, "5900") {
			t.Fatal("backend must not expose a TCP listener")
		}
		if name == "x11vnc" && !strings.Contains(args, "-rfbport 0") {
			t.Fatal("x11vnc TCP must be disabled")
		}
	}
	if _, _, err := screenBackend("unknown", dir); err == nil {
		t.Fatal("unknown backend accepted")
	}
}

// Linux CI uses a fresh synthetic X server, never the operator's desktop.
func TestX11BackendSpeaksRFBThroughPrivateUnixSocket(t *testing.T) {
	if runtime.GOOS != "linux" {
		t.Skip("Linux X11 backend")
	}
	for _, name := range []string{"Xvfb", "x11vnc"} {
		if _, err := exec.LookPath(name); err != nil {
			t.Skip("Install Xvfb and x11vnc for the synthetic desktop check")
		}
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	x := exec.CommandContext(ctx, "Xvfb", "-displayfd", "1", "-screen", "0", "320x180x24", "-nolisten", "tcp")
	output, err := x.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	if err = x.Start(); err != nil {
		t.Fatal(err)
	}
	defer func() { x.Process.Kill(); x.Wait() }()
	display, err := bufio.NewReader(io.LimitReader(output, 64)).ReadString('\n')
	if err != nil {
		t.Fatal(err)
	}
	dir, err := os.MkdirTemp("", "un-")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(dir)
	cmd, address, err := screenBackend("x11vnc", dir)
	if err != nil {
		t.Fatal(err)
	}
	cmd.Env = append(os.Environ(), "DISPLAY=:"+strings.TrimSpace(display))
	if err = cmd.Start(); err != nil {
		t.Fatal(err)
	}
	defer func() { cmd.Process.Kill(); cmd.Wait() }()
	var conn net.Conn
	for deadline := time.Now().Add(8 * time.Second); time.Now().Before(deadline); {
		conn, err = net.DialTimeout("unix", address, 100*time.Millisecond)
		if err == nil {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	if err != nil {
		t.Fatal("backend did not open private RFB socket", err)
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(5 * time.Second))
	version := make([]byte, 12)
	if _, err = io.ReadFull(conn, version); err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(string(version), "RFB 003.") {
		t.Fatal("invalid RFB greeting")
	}
}
