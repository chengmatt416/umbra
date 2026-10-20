package node

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestPairingProofParityAndHostConfirmation(t *testing.T) {
	h, e := OpenHome(t.TempDir())
	if e != nil {
		t.Fatal(e)
	}
	node, phone := strings.Repeat("a", 64), strings.Repeat("b", 64)
	if got := Proof("phone", "k7q2 9fxa m3pd", node, phone); got != "084d2795bba7c0717bf472e3a70e096f5c4b08911bf034c6e35cc62062cd69dc" {
		t.Fatal(got)
	}
	if got := Proof("node", "K7Q2-9FXA-M3PD", node, phone); got != "c5fcfcc6fb94ab3ba2b5b771c7d681b167bf627955a87e093845316f7fe8a7b0" {
		t.Fatal(got)
	}
	p := new(Pairing)
	p.Open()
	inv, _, _ := p.Snapshot(h, nil)
	result := make(chan error, 1)
	go func() {
		_, e := p.Pair(h, phone, "test phone", Proof("phone", inv.Code, h.Fingerprint, phone))
		result <- e
	}()
	var pending *Pending
	for i := 0; i < 100; i++ {
		_, pending, _ = p.Snapshot(h, nil)
		if pending != nil {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if pending == nil {
		t.Fatal("no pending confirmation")
	}
	if h.Paired(phone) {
		t.Fatal("trusted before host approval")
	}
	if e = p.Decide(pending.ID, true); e != nil {
		t.Fatal(e)
	}
	if e = <-result; e != nil {
		t.Fatal(e)
	}
	if !h.Paired(phone) || p.IsOpen() {
		t.Fatal("pairing did not consume code")
	}
	if _, e = p.Pair(h, "stranger", "x", Proof("phone", inv.Code, h.Fingerprint, "stranger")); e == nil {
		t.Fatal("replayed code accepted")
	}
}
func TestWrongCodesConsumeWindowAndDeclineDoesNotTrust(t *testing.T) {
	h, _ := OpenHome(t.TempDir())
	p := new(Pairing)
	p.Open()
	for range 5 {
		if _, e := p.Pair(h, "fp", "phone", "wrong"); e == nil {
			t.Fatal("wrong code accepted")
		}
	}
	if p.IsOpen() {
		t.Fatal("window still open")
	}
	phones, _ := h.Phones()
	if len(phones) != 0 {
		t.Fatal(phones)
	}
}
func TestManifestUploadHashesConfinementAndOwnership(t *testing.T) {
	root := t.TempDir()
	j := NewJobs(root, "epoch")
	data := []byte("input\n")
	sum := sha256.Sum256(data)
	entry := ManifestFile{"in.txt", int64(len(data)), hex.EncodeToString(sum[:])}
	m := Manifest{Allowed: []string{"in.txt"}, Files: []ManifestFile{entry}, MaxBytes: 1024, Retention: "delete-after-job"}
	if e := j.Manifest("job-1", "phone", m); e != nil {
		t.Fatal(e)
	}
	if e := j.Put("job-1", "stranger", "in.txt", 0, base64.StdEncoding.EncodeToString(data), true); e == nil {
		t.Fatal("other phone wrote input")
	}
	if e := j.Put("job-1", "phone", "../outside", 0, "", true); e == nil {
		t.Fatal("path escaped")
	}
	if e := j.Put("job-1", "phone", "in.txt", 0, base64.StdEncoding.EncodeToString(data), true); e != nil {
		t.Fatal(e)
	}
	os.WriteFile(filepath.Join(root, "job-1/files/in.txt"), []byte("tampered"), 0600)
	if _, e := j.Run("job-1", "phone", []string{"cat", "in.txt"}, ".", 10000); e == nil {
		t.Fatal("tampered input ran")
	}
	os.Remove(filepath.Join(root, "job-1/files/in.txt"))
	if e := j.Put("job-1", "phone", "in.txt", 0, base64.StdEncoding.EncodeToString(data), true); e != nil {
		t.Fatal(e)
	}
	if _, e := j.Run("job-1", "phone", []string{"cat", "in.txt"}, ".", 10000); e != nil {
		t.Fatal(e)
	}
	waitJob(t, j, "phone", "EXITED")
	if j.List("phone")[0].Output != "input\n" {
		t.Fatal(j.List("phone"))
	}
	if len(j.List("stranger")) != 0 {
		t.Fatal("jobs leaked")
	}
	if e := j.Forget("job-1", "phone"); e != nil {
		t.Fatal(e)
	}
	if _, e := os.Stat(filepath.Join(root, "job-1")); !os.IsNotExist(e) {
		t.Fatal("files retained")
	}
}
func waitJob(t *testing.T, j *Jobs, owner, state string) {
	t.Helper()
	for i := 0; i < 100; i++ {
		list := j.List(owner)
		if len(list) > 0 && list[0].State == state {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("expected %s, got %+v", state, j.List(owner))
}
func TestLeaseExpiresAndEnvironmentDoesNotInheritSecrets(t *testing.T) {
	t.Setenv("UMBRA_TEST_SECRET", "do-not-inherit")
	j := NewJobs(t.TempDir(), "epoch")
	if _, e := j.Run("env", "phone", []string{"sh", "-c", "printf '%s' \"${UMBRA_TEST_SECRET-unset}\""}, "", 1000); e != nil {
		t.Fatal(e)
	}
	waitJob(t, j, "phone", "EXITED")
	if j.List("phone")[0].Output != "unset" {
		t.Fatal("inherited secret")
	}
	lease := NewJobs(t.TempDir(), "epoch")
	if _, e := lease.Run("lease", "phone", []string{"sh", "-c", "sleep 20 & wait"}, "", 20); e != nil {
		t.Fatal(e)
	}
	time.Sleep(50 * time.Millisecond)
	lease.Expire()
	waitJob(t, lease, "phone", "LEASE_EXPIRED")
	if _, e := lease.Renew("lease", "phone", 0); e == nil {
		t.Fatal("zero renewal accepted")
	}
}
func TestSymlinksCeilingsAndDuplicatePathsAreRefused(t *testing.T) {
	root := t.TempDir()
	outside := t.TempDir()
	os.Symlink(outside, filepath.Join(root, "escape"))
	if _, e := safePath(root, "escape/file"); e == nil {
		t.Fatal("symlink accepted")
	}
	j := NewJobs(t.TempDir(), "epoch")
	m := Manifest{Allowed: []string{"x"}, Files: []ManifestFile{{"x", MaxUpload + 1, strings.Repeat("a", 64)}}, MaxBytes: MaxUpload, Retention: "delete-after-job"}
	if e := j.Manifest("j", "p", m); e == nil {
		t.Fatal("ceiling ignored")
	}
	m.Files = []ManifestFile{{"x", 0, strings.Repeat("a", 64)}, {"x", 0, strings.Repeat("a", 64)}}
	if e := j.Manifest("j", "p", m); e == nil {
		t.Fatal("duplicates accepted")
	}
}
func TestRestartMarksRunningJobLostAndIdentityPersists(t *testing.T) {
	dir := t.TempDir()
	h, _ := OpenHome(dir)
	again, e := OpenHome(dir)
	if e != nil || h.Fingerprint != again.Fingerprint {
		t.Fatal("identity changed")
	}
	root := filepath.Join(dir, "jobs")
	j := NewJobs(root, "before")
	r, e := j.record("j", "p", true)
	if e != nil {
		t.Fatal(e)
	}
	r.Status.State = "RUNNING"
	j.save(r)
	after := NewJobs(root, "after")
	if after.List("p")[0].State != "LOST" {
		t.Fatal("restart assumed completion")
	}
}
