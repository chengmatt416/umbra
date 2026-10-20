package node

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"math/big"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

const Port = 47100
const MaxUpload = int64(50 * 1024 * 1024)

var Version = "0.4.4"

type Config struct {
	NodeID      string `json:"nodeId"`
	Name        string `json:"name"`
	Port        int    `json:"port"`
	Screen      string `json:"screen"`
	VNCAddress  string `json:"vncAddress"`
	AgentScreen bool   `json:"agentScreen"`
	KeepAwake   bool   `json:"keepAwake"`
}
type Phone struct {
	Fingerprint string `json:"fingerprint"`
	Name        string `json:"name"`
	PairedAt    int64  `json:"pairedAtMs"`
}
type Home struct {
	Dir         string
	mu          sync.Mutex
	Config      Config
	Cert        tls.Certificate
	Fingerprint string
}

func OpenHome(dir string) (*Home, error) {
	if err := os.MkdirAll(filepath.Join(dir, "jobs"), 0700); err != nil {
		return nil, err
	}
	if err := os.Chmod(dir, 0700); err != nil {
		return nil, err
	}
	h := &Home{Dir: dir}
	raw, err := os.ReadFile(filepath.Join(dir, "node.json"))
	if err == nil {
		if err = json.Unmarshal(raw, &h.Config); err != nil {
			return nil, err
		}
	} else if !os.IsNotExist(err) {
		return nil, err
	}
	if h.Config.NodeID == "" {
		var id [16]byte
		rand.Read(id[:])
		host, _ := os.Hostname()
		h.Config = Config{NodeID: hex.EncodeToString(id[:]), Name: host, Port: Port}
	}
	if h.Config.Port == 0 {
		h.Config.Port = Port
	}
	if err = h.SaveConfig(h.Config); err != nil {
		return nil, err
	}
	certPath, keyPath := filepath.Join(dir, "node.crt"), filepath.Join(dir, "node.key")
	if _, err = os.Stat(keyPath); os.IsNotExist(err) {
		if _, old := os.Stat(filepath.Join(dir, "node.p12")); old == nil {
			// Keep the JVM runner's identity so previously paired phones keep their certificate pin.
			pass, e := os.ReadFile(filepath.Join(dir, "node.pass"))
			if e != nil {
				return nil, e
			}
			cmd := exec.Command("openssl", "pkcs12", "-in", filepath.Join(dir, "node.p12"), "-nodes", "-passin", "stdin")
			cmd.Stdin = strings.NewReader(strings.TrimSpace(string(pass)) + "\n")
			out, e := cmd.Output()
			if e != nil {
				return nil, fmt.Errorf("cannot migrate JVM identity with openssl: %w; existing identity has been preserved", e)
			}
			var cert, key []byte
			for len(out) > 0 {
				b, rest := pem.Decode(out)
				if b == nil {
					break
				}
				out = rest
				if b.Type == "CERTIFICATE" {
					cert = append(cert, pem.EncodeToMemory(b)...)
				}
				if strings.Contains(b.Type, "PRIVATE KEY") {
					key = pem.EncodeToMemory(b)
				}
			}
			if len(cert) == 0 || len(key) == 0 {
				return nil, errors.New("JVM identity could not be decoded; preserved")
			}
			if _, e = tls.X509KeyPair(cert, key); e != nil {
				return nil, e
			}
			if e = atomicWrite(keyPath, key); e != nil {
				return nil, e
			}
			if e = atomicWrite(certPath, cert); e != nil {
				return nil, e
			}
		} else {
			key, e := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
			if e != nil {
				return nil, e
			}
			serial, _ := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
			template := &x509.Certificate{SerialNumber: serial, Subject: pkix.Name{CommonName: "Umbra node " + h.Config.NodeID}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().AddDate(10, 0, 0), KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth, x509.ExtKeyUsageClientAuth}}
			der, e := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
			if e != nil {
				return nil, e
			}
			pk, e := x509.MarshalPKCS8PrivateKey(key)
			if e != nil {
				return nil, e
			}
			if e = atomicWrite(keyPath, pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: pk})); e != nil {
				return nil, e
			}
			if e = atomicWrite(certPath, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})); e != nil {
				return nil, e
			}
		}
	}
	h.Cert, err = tls.LoadX509KeyPair(certPath, keyPath)
	if err != nil {
		return nil, err
	}
	h.Fingerprint = fingerprint(h.Cert.Certificate[0])
	return h, nil
}
func fingerprint(der []byte) string { h := sha256.Sum256(der); return hex.EncodeToString(h[:]) }
func atomicWrite(path string, data []byte) error {
	tmp := path + ".new"
	if err := os.WriteFile(tmp, data, 0600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}
func writeJSON(path string, v any) error {
	b, e := json.Marshal(v)
	if e != nil {
		return e
	}
	return atomicWrite(path, b)
}
func (h *Home) Settings() Config { h.mu.Lock(); defer h.mu.Unlock(); return h.Config }
func (h *Home) SaveConfig(c Config) error {
	h.mu.Lock()
	defer h.mu.Unlock()
	if e := writeJSON(filepath.Join(h.Dir, "node.json"), c); e != nil {
		return e
	}
	h.Config = c
	return nil
}
func (h *Home) Phones() ([]Phone, error) { h.mu.Lock(); defer h.mu.Unlock(); return h.phones() }
func (h *Home) phones() ([]Phone, error) {
	p := []Phone{}
	b, e := os.ReadFile(filepath.Join(h.Dir, "paired.json"))
	if os.IsNotExist(e) {
		return p, nil
	}
	if e != nil {
		return nil, e
	}
	e = json.Unmarshal(b, &p)
	return p, e
}
func (h *Home) Paired(fp string) bool {
	p, e := h.Phones()
	if e != nil {
		return false
	}
	for _, v := range p {
		if v.Fingerprint == fp {
			return true
		}
	}
	return false
}
func (h *Home) SetPhone(p Phone, add bool) error {
	h.mu.Lock()
	defer h.mu.Unlock()
	list, e := h.phones()
	if e != nil {
		return e
	}
	next := []Phone{}
	for _, v := range list {
		if v.Fingerprint != p.Fingerprint {
			next = append(next, v)
		}
	}
	if add {
		next = append(next, p)
	}
	return writeJSON(filepath.Join(h.Dir, "paired.json"), next)
}
