package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/signal"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"syscall"
	"time"

	"app.umbra/node/internal/node"
	tea "github.com/charmbracelet/bubbletea"
	"github.com/skip2/go-qrcode"
	"golang.org/x/term"
)

func option(args []string, name, defaultValue string) string {
	for i, a := range args {
		if a == name && i+1 < len(args) {
			return args[i+1]
		}
	}
	return defaultValue
}
func has(args []string, s string) bool {
	for _, a := range args {
		if a == s {
			return true
		}
	}
	return false
}
func main() {
	if e := run(os.Args[1:]); e != nil {
		fmt.Fprintln(os.Stderr, e)
		os.Exit(1)
	}
}
func run(args []string) error {
	home, _ := os.UserHomeDir()
	home = option(args, "--home", filepath.Join(home, ".umbra-node"))
	cmd := "setup"
	if len(args) > 0 {
		cmd = args[0]
	}
	switch cmd {
	case "verify-release":
		if len(args) != 3 {
			return errors.New("usage: umbra-node verify-release FILE FILE.minisig")
		}
		return node.VerifyRelease(args[1], args[2])
	case "version", "--version":
		fmt.Println(node.Version)
		return nil
	case "serve":
		h, e := node.OpenHome(home)
		if e != nil {
			return e
		}
		port, e := strconv.Atoi(option(args, "--port", strconv.Itoa(h.Settings().Port)))
		if e != nil || port < 0 || port > 65535 {
			return errors.New("invalid port")
		}
		s, e := node.Start(h, port, has(args, "--pair"))
		if e != nil {
			return e
		}
		defer s.Close()
		if e = s.Control(); e != nil {
			return e
		}
		fmt.Printf("Umbra Node %s · %s · port %d\n", node.Version, h.Settings().Name, s.Port())
		if has(args, "--pair") {
			v := s.State()
			fmt.Printf("Pairing: %s\n%s\n", v.Invitation.Code, v.URI)
		}
		signalCh := make(chan os.Signal, 1)
		signal.Notify(signalCh, syscall.SIGINT, syscall.SIGTERM)
		defer signal.Stop(signalCh)
		select {
		case <-signalCh:
		case <-s.Done():
		}
		return nil
	case "status":
		var state node.SetupState
		if e := node.Control(home, "/state", "GET", nil, &state); e != nil {
			return fmt.Errorf("node is offline; run umbra-node setup: %w", e)
		}
		if has(args, "--json") {
			return json.NewEncoder(os.Stdout).Encode(state)
		}
		fmt.Printf("%s · online · %s/%s\n%s\n", state.Name, state.OS, state.Arch, state.Workspace)
		for _, a := range state.Addresses {
			fmt.Printf("  %s %s:%d\n", a.Kind, a.Host, a.Port)
		}
		fmt.Printf("  %d paired phone(s) · screen: %s\n", len(state.Phones), state.Screen.Detail)
		return nil
	case "setup":
		if _, e := node.EnsureRunning(home); e != nil {
			return e
		}
		w := &Wizard{Home: home}
		if _, e := w.Action(`{"action":"pair-open"}`); e != nil {
			return e
		}
		if has(args, "--gui") || !has(args, "--tui") && !term.IsTerminal(int(os.Stdin.Fd())) {
			return runGUI(w)
		}
		_, e := tea.NewProgram(model{wizard: w}, tea.WithAltScreen()).Run()
		return e
	case "uninstall":
		if !has(args, "--confirm") {
			return errors.New("run umbra-node uninstall --confirm to stop the node and revoke pairings; job files are preserved")
		}
		node.InstallService(home, false)
		var state node.SetupState
		node.Control(home, "/action", "POST", strings.NewReader(`{"action":"stop"}`), &state)
		time.Sleep(400 * time.Millisecond)
		h, e := node.OpenHome(home)
		if e != nil {
			return e
		}
		phones, e := h.Phones()
		if e != nil {
			return e
		}
		for _, p := range phones {
			if e = h.SetPhone(p, false); e != nil {
				return e
			}
		}
		fmt.Println("Background service removed and pairings revoked. Job files preserved in " + filepath.Join(home, "jobs"))
		return nil
	default:
		return errors.New("usage: umbra-node setup [--gui|--tui] | serve [--pair] | status [--json] | uninstall --confirm | version")
	}
}

type Wizard struct{ Home string }

func (w *Wizard) State() (node.SetupState, error) {
	var s node.SetupState
	e := node.Control(w.Home, "/state", "GET", nil, &s)
	return s, e
}
func (w *Wizard) Action(value string) (node.SetupState, error) {
	var s node.SetupState
	e := node.Control(w.Home, "/action", "POST", bytes.NewBufferString(value), &s)
	return s, e
}

type refresh struct {
	s   node.SetupState
	err error
}
type tick time.Time
type model struct {
	wizard *Wizard
	state  node.SetupState
	step   int
	err    string
	width  int
	height int
}

func nextTick() tea.Cmd       { return tea.Tick(time.Second, func(t time.Time) tea.Msg { return tick(t) }) }
func (m model) Init() tea.Cmd { return tea.Batch(m.refresh(), nextTick()) }
func (m model) refresh() tea.Cmd {
	return func() tea.Msg { s, e := m.wizard.State(); return refresh{s, e} }
}
func (m model) action(a node.Frame) tea.Cmd {
	return func() tea.Msg { b, _ := json.Marshal(a); s, e := m.wizard.Action(string(b)); return refresh{s, e} }
}
func (m model) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	switch v := msg.(type) {
	case refresh:
		if v.err != nil {
			m.err = v.err.Error()
		} else {
			m.state = v.s
			m.err = ""
		}
		return m, nil
	case tick:
		return m, tea.Batch(m.refresh(), nextTick())
	case tea.WindowSizeMsg:
		m.width = v.Width
		m.height = v.Height
	case tea.KeyMsg:
		switch v.String() {
		case "q", "ctrl+c":
			return m, tea.Quit
		case "esc", "left":
			if m.step > 0 {
				m.step--
			}
		case "enter", "right", "s":
			if m.state.Pending != nil && m.step == 1 && v.String() == "enter" {
				return m, m.action(node.Frame{"action": "pair-decision", "id": m.state.Pending.ID, "allow": true})
			}
			if m.step == 5 {
				return m, tea.Quit
			}
			m.step++
		case "y":
			if m.step == 1 && m.state.Pending != nil {
				return m, m.action(node.Frame{"action": "pair-decision", "id": m.state.Pending.ID, "allow": true})
			}
		case "n":
			if m.step == 1 && m.state.Pending != nil {
				return m, m.action(node.Frame{"action": "pair-decision", "id": m.state.Pending.ID, "allow": false})
			}
		case "r":
			if m.step == 1 {
				return m, m.action(node.Frame{"action": "pair-open"})
			}
		case "1", "2", "3":
			if m.step == 2 {
				backend := ""
				if v.String() == "1" {
					if runtime.GOOS == "darwin" {
						backend = "native"
					} else {
						backend = "x11vnc"
					}
				} else if v.String() == "2" {
					backend = "wayvnc"
				}
				return m, m.action(node.Frame{"action": "screen", "backend": backend})
			}
		case "p":
			if m.step == 2 {
				return m, m.action(node.Frame{"action": "permission", "kind": "screen"})
			}
		case "a":
			if m.step == 2 {
				return m, m.action(node.Frame{"action": "permission", "kind": "accessibility"})
			}
			if m.step == 4 {
				return m, m.action(node.Frame{"action": "settings", "agentScreen": !m.state.Config.AgentScreen, "keepAwake": m.state.Config.KeepAwake})
			}
		case "b":
			if m.step == 4 {
				return m, m.action(node.Frame{"action": "background", "enabled": !m.state.Background})
			}
		case "k":
			if m.step == 4 {
				return m, m.action(node.Frame{"action": "settings", "agentScreen": m.state.Config.AgentScreen, "keepAwake": !m.state.Config.KeepAwake})
			}
		}
	}
	return m, nil
}
func (m model) View() string {
	steps := []string{"檢查", "配對", "畫面存取", "連線", "背景執行", "完成"}
	var b strings.Builder
	b.WriteString("\n Umbra Node 設定 · " + node.Version + "\n\n ")
	for i, s := range steps {
		mark := "○"
		if i < m.step {
			mark = "●"
		}
		if i == m.step {
			mark = "◉"
		}
		b.WriteString(mark + " " + s + "   ")
	}
	b.WriteString("\n\n ")
	s := m.state
	switch m.step {
	case 0:
		fmt.Fprintf(&b, "先看看這台電腦\n\n %s · %s / %s\n 剩 %d GB · %s\n\n", s.Name, s.OS, s.Arch, s.FreeGB, s.Workspace)
		for _, a := range s.Addresses {
			fmt.Fprintf(&b, " %s · %s:%d\n", a.Kind, a.Host, a.Port)
		}
		b.WriteString("\n " + s.Screen.Detail)
	case 1:
		b.WriteString("用手機掃這個 QR\n\n")
		if qr, e := qrcode.New(s.URI, qrcode.Medium); e == nil {
			size := len(qr.Bitmap())
			if m.width >= size+4 && m.height >= size/2+22 {
				b.WriteString(qr.ToSmallString(false))
			} else {
				b.WriteString(" 視窗太小，無法完整顯示 QR。放大終端機，或在手機輸入下方位址與配對碼。\n")
			}
		}
		for _, a := range s.Addresses {
			fmt.Fprintf(&b, " %s · %s:%d\n", a.Kind, a.Host, a.Port)
		}
		fmt.Fprintf(&b, "\n 在手機：Umbra → 節點 → 新增節點（所有模式）\n 或輸入配對碼 %s\n 憑證 SHA-256 %s\n 10 分鐘有效 · 還能試 %d 次 · r 重新產生\n", s.Invitation.Code, s.Invitation.Fingerprint, s.Attempts)
		if s.Pending != nil {
			fmt.Fprintf(&b, "\n 「%s」想配對。兩邊名字都對才繼續。\n y 配對 / n 拒絕\n", s.Pending.Name)
		} else {
			b.WriteString("\n 等待手機…\n")
		}
	case 2:
		b.WriteString("讓手機看得到這台的畫面\n\n " + s.Screen.Detail + "\n\n")
		if runtime.GOOS == "darwin" {
			b.WriteString(" 1 ScreenCaptureKit（macOS 14+，需要時才擷取）\n p 打開螢幕錄製設定 · a 輔助使用（點擊、打字）\n")
		} else {
			b.WriteString(" 1 x11vnc（X11） · 2 wayvnc（wlroots Wayland）\n 套件需先用系統套件管理器安裝。GNOME Wayland 不提供 VNC 時只能跑工作。\n")
		}
		b.WriteString(" 3 關閉畫面 · s 略過，只跑工作\n 畫面經已配對的 mTLS 通道傳送；VNC 只聽本機。")
	case 3:
		b.WriteString("手機怎麼找到這台\n\n")
		for _, a := range s.Addresses {
			fmt.Fprintf(&b, " %s · %s:%d\n", a.Kind, a.Host, a.Port)
		}
		if s.LastSeen > 0 {
			fmt.Fprintf(&b, "\n 最近手機連線：%s\n", time.UnixMilli(s.LastSeen).Format(time.RFC3339))
		} else {
			b.WriteString("\n 尚未從手機測試。打開手機的節點詳情會送出測試。\n")
		}
		b.WriteString(" 出門前先讓手機與電腦連上同一個 VPN / Tailscale。")
	case 4:
		fmt.Fprintf(&b, "在背景一直開著\n\n b 登入時啟動：%t\n   %s\n a 讓代理看畫面：%t\n k 工作中防止睡眠：%t\n", s.Background, s.ServicePath, s.Config.AgentScreen, s.Config.KeepAwake)
	case 5:
		fmt.Fprintf(&b, "好了 · %s\n\n 線上 · %d 個已配對手機\n 畫面 %s\n 信任：有限（在手機上調高才會放工作）\n\n umbra-node status\n umbra-node setup\n umbra-node uninstall --confirm\n", s.Name, len(s.Phones), s.Screen.Detail)
	}
	if m.err != "" {
		b.WriteString("\n\n ⚠ " + m.err)
	}
	b.WriteString("\n\n ──────────────────────────────────────────\n ↵ 下一步   s 略過   esc 返回   q 離開\n 配對只證明身分；工作以你的使用者身分執行。\n")
	return b.String()
}
