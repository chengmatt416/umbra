package node

import (
	"crypto/hmac"
	"crypto/pbkdf2"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"strings"
	"sync"
	"time"
)

type Address struct {
	Host string `json:"host"`
	Port int    `json:"port"`
	Kind string `json:"kind"`
}
type Invitation struct {
	Version     int       `json:"version"`
	Name        string    `json:"name"`
	Fingerprint string    `json:"fingerprint"`
	Code        string    `json:"code"`
	Expires     int64     `json:"expiresAtMs"`
	Addresses   []Address `json:"addresses"`
}

func (i Invitation) URI() string {
	b, _ := json.Marshal(i)
	return "umbra-node://pair?data=" + base64.RawURLEncoding.EncodeToString(b)
}
func normalize(code string) string {
	var b strings.Builder
	for _, c := range strings.ToUpper(code) {
		if c == 'I' || c == 'L' {
			c = '1'
		}
		if c == 'O' {
			c = '0'
		}
		if c >= '0' && c <= '9' || c >= 'A' && c <= 'Z' {
			b.WriteRune(c)
		}
	}
	return b.String()
}
func Proof(role, code, node, phone string) string {
	key, _ := pbkdf2.Key(sha256.New, normalize(code), []byte("umbra-node-pairing:"+node), 120000, 32)
	m := hmac.New(sha256.New, key)
	m.Write([]byte(role + "|" + node + "|" + phone))
	return hex.EncodeToString(m.Sum(nil))
}

type Pending struct {
	ID          string `json:"id"`
	Name        string `json:"name"`
	Fingerprint string `json:"fingerprint"`
	reply       chan bool
}
type Pairing struct {
	mu       sync.Mutex
	code     string
	expires  time.Time
	attempts int
	pending  *Pending
	used     bool
}

func (p *Pairing) Open() {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.pending != nil {
		p.pending.reply <- false
		p.pending = nil
	}
	var b [12]byte
	rand.Read(b[:])
	const alpha = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
	var c strings.Builder
	for i, v := range b {
		if i > 0 && i%4 == 0 {
			c.WriteByte('-')
		}
		c.WriteByte(alpha[int(v)%32])
	}
	p.code = c.String()
	p.expires = time.Now().Add(10 * time.Minute)
	p.attempts = 5
	p.used = false
}
func (p *Pairing) IsOpen() bool { p.mu.Lock(); defer p.mu.Unlock(); return p.open() }
func (p *Pairing) open() bool   { return !p.used && p.attempts > 0 && time.Now().Before(p.expires) }
func (p *Pairing) Snapshot(h *Home, addresses []Address) (Invitation, *Pending, int) {
	p.mu.Lock()
	defer p.mu.Unlock()
	c := h.Settings()
	return Invitation{1, c.Name, h.Fingerprint, p.code, p.expires.UnixMilli(), addresses}, p.pending, p.attempts
}
func (p *Pairing) Decide(id string, allow bool) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.pending == nil || p.pending.ID != id {
		return errors.New("pairing request expired")
	}
	p.pending.reply <- allow
	p.pending = nil
	return nil
}
func (p *Pairing) Pair(h *Home, phone, name, proof string) (string, error) {
	p.mu.Lock()
	if !p.open() {
		p.mu.Unlock()
		return "", errors.New("pairing is closed; open setup again")
	}
	expected := Proof("phone", p.code, h.Fingerprint, phone)
	if !hmac.Equal([]byte(proof), []byte(expected)) {
		p.attempts--
		p.mu.Unlock()
		return "", errors.New("wrong pairing code")
	}
	if p.pending != nil {
		p.mu.Unlock()
		return "", errors.New("another phone is awaiting confirmation")
	}
	var id [12]byte
	rand.Read(id[:])
	request := &Pending{hex.EncodeToString(id[:]), name, phone, make(chan bool, 1)}
	p.pending = request
	code := p.code
	p.mu.Unlock()
	timer := time.NewTimer(110 * time.Second)
	defer timer.Stop()
	allow := false
	select {
	case allow = <-request.reply:
	case <-timer.C:
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.pending == request {
		p.pending = nil
	}
	if !allow || !p.open() || p.code != code {
		return "", errors.New("pairing was declined or expired on the computer")
	}
	if err := h.SetPhone(Phone{phone, name, time.Now().UnixMilli()}, true); err != nil {
		return "", err
	}
	p.used = true
	return Proof("node", code, h.Fingerprint, phone), nil
}
