package node

import (
	_ "embed"
	"errors"
	"io"
	"os"

	"aead.dev/minisign"
)

//go:embed release.pub
var ReleasePublicKey []byte

// Used by the installer after its pinned SHA-256 bootstrap, before installing anything.
func VerifyRelease(file, signature string) error {
	var key minisign.PublicKey
	if e := key.UnmarshalText(ReleasePublicKey); e != nil {
		return e
	}
	sig, e := os.ReadFile(signature)
	if e != nil {
		return e
	}
	input, e := os.Open(file)
	if e != nil {
		return e
	}
	defer input.Close()
	reader := minisign.NewReader(input)
	if _, e = io.Copy(io.Discard, reader); e != nil {
		return e
	}
	if !reader.Verify(key, sig) {
		return errors.New("release signature verification failed")
	}
	return nil
}
