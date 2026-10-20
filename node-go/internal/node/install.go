package node

import (
	"crypto/sha256"
	"encoding/hex"
	"io"
	"os"
	"path/filepath"
	"strings"
)

// Desktop downloads may run from a temporary DMG/AppImage mount. A daemon and login service need a durable path.
func stableExecutable(home string) (string, error) {
	exe, e := os.Executable()
	if e != nil {
		return "", e
	}
	source := exe
	suffix := ""
	if appimage := os.Getenv("APPIMAGE"); appimage != "" {
		source = appimage
		suffix = ".AppImage"
	} else if !strings.Contains(exe, ".app/Contents/MacOS/") {
		return exe, nil
	}
	target := filepath.Join(home, "bin", "umbra-node-"+Version+suffix)
	if source == target {
		return target, nil
	}
	input, e := os.Open(source)
	if e != nil {
		return "", e
	}
	defer input.Close()
	sum := sha256.New()
	if _, e = io.Copy(sum, input); e != nil {
		return "", e
	}
	digest := hex.EncodeToString(sum.Sum(nil))
	target = filepath.Join(home, "bin", "umbra-node-"+Version+"-"+digest[:12]+suffix)
	if _, e = os.Stat(target); e == nil {
		return target, nil
	}
	if e = os.MkdirAll(filepath.Dir(target), 0700); e != nil {
		return "", e
	}
	if _, e = input.Seek(0, 0); e != nil {
		return "", e
	}
	tmp := target + ".new"
	out, e := os.OpenFile(tmp, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0700)
	if e != nil {
		return "", e
	}
	_, e = io.Copy(out, input)
	closeErr := out.Close()
	if e != nil {
		return "", e
	}
	if closeErr != nil {
		return "", closeErr
	}
	if e = os.Rename(tmp, target); e != nil {
		return "", e
	}
	return target, nil
}
