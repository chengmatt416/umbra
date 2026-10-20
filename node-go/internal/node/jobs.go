package node

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"syscall"
	"time"
)

type ManifestFile struct {
	Path  string `json:"path"`
	Bytes int64  `json:"bytes"`
	SHA   string `json:"sha256"`
}
type Manifest struct {
	Allowed   []string       `json:"allowed"`
	Files     []ManifestFile `json:"files"`
	MaxBytes  int64          `json:"maxBytes"`
	Secrets   []string       `json:"secrets"`
	Retention string         `json:"retention"`
}
type JobStatus struct {
	Job     string `json:"job"`
	State   string `json:"state"`
	Exit    *int   `json:"exit"`
	Started int64  `json:"startedAtMs"`
	Ended   *int64 `json:"endedAtMs"`
	Lease   int64  `json:"leaseUntilMs"`
	Epoch   string `json:"epoch"`
	Output  string `json:"outputTail"`
	Note    string `json:"note"`
}
type jobRecord struct {
	Status JobStatus `json:"status"`
	Owner  string    `json:"owner"`
	cmd    *exec.Cmd
	mu     sync.Mutex
}
type Jobs struct {
	root, epoch string
	mu          sync.Mutex
	records     map[string]*jobRecord
}

var jobPattern = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`)

func NewJobs(root, epoch string) *Jobs {
	j := &Jobs{root: root, epoch: epoch, records: map[string]*jobRecord{}}
	dirs, _ := os.ReadDir(root)
	for _, d := range dirs {
		if !d.IsDir() || !jobPattern.MatchString(d.Name()) {
			continue
		}
		b, e := os.ReadFile(filepath.Join(root, d.Name(), "record.json"))
		if e != nil {
			continue
		}
		r := new(jobRecord)
		if json.Unmarshal(b, r) != nil {
			continue
		}
		if r.Status.State == "RUNNING" {
			r.Status.State = "LOST"
			r.Status.Note = "Runner restarted; previous process is no longer watched"
		}
		j.records[d.Name()] = r
		j.save(r)
	}
	return j
}
func (j *Jobs) save(r *jobRecord) error {
	return writeJSON(filepath.Join(j.root, r.Status.Job, "record.json"), r)
}
func (j *Jobs) record(id, owner string, create bool) (*jobRecord, error) {
	if !jobPattern.MatchString(id) || id == "." || id == ".." {
		return nil, errors.New("invalid job id")
	}
	j.mu.Lock()
	defer j.mu.Unlock()
	r := j.records[id]
	if r == nil && create {
		r = &jobRecord{Status: JobStatus{Job: id, State: "PREPARING", Epoch: j.epoch}, Owner: owner}
		if e := os.MkdirAll(filepath.Join(j.root, id, "files"), 0700); e != nil {
			return nil, e
		}
		j.records[id] = r
	}
	if r == nil || r.Owner != owner {
		return nil, errors.New("no job owned by this phone")
	}
	return r, nil
}
func safePath(root, path string) (string, error) {
	if path == "" || filepath.IsAbs(path) || strings.Contains(path, "\\") {
		return "", errors.New("invalid relative path")
	}
	for _, v := range strings.Split(path, "/") {
		if v == ".." || v == "." || v == "" {
			return "", errors.New("invalid path component")
		}
	}
	target := filepath.Join(root, filepath.FromSlash(path))
	rel, e := filepath.Rel(root, target)
	if e != nil || rel == ".." || strings.HasPrefix(rel, "../") {
		return "", errors.New("path leaves job folder")
	}
	current := root
	for _, p := range strings.Split(filepath.FromSlash(path), string(os.PathSeparator)) {
		current = filepath.Join(current, p)
		s, e := os.Lstat(current)
		if e == nil && s.Mode()&os.ModeSymlink != 0 {
			return "", errors.New("symlinks are refused")
		}
		if e != nil && !os.IsNotExist(e) {
			return "", e
		}
	}
	return target, nil
}
func (j *Jobs) Manifest(id, owner string, m Manifest) error {
	r, e := j.record(id, owner, true)
	if e != nil {
		return e
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.Status.State != "PREPARING" {
		return errors.New("job id already used")
	}
	if m.MaxBytes < 0 || m.MaxBytes > MaxUpload || len(m.Secrets) > 0 || m.Retention != "delete-after-job" {
		return errors.New("manifest policy refused")
	}
	var total int64
	seen := map[string]bool{}
	for _, f := range m.Files {
		if _, e = safePath(filepath.Join(j.root, id, "files"), f.Path); e != nil {
			return e
		}
		if f.Bytes < 0 || f.Bytes > m.MaxBytes-total || len(f.SHA) != 64 || seen[f.Path] {
			return errors.New("manifest sizes, hashes or paths refused")
		}
		if _, e = hex.DecodeString(f.SHA); e != nil {
			return e
		}
		allowed := false
		for _, a := range m.Allowed {
			if _, e = safePath("/manifest", a); e != nil {
				return e
			}
			if f.Path == a || strings.HasPrefix(f.Path, a+"/") {
				allowed = true
			}
		}
		if !allowed {
			return errors.New("file is outside allowlist")
		}
		total += f.Bytes
		seen[f.Path] = true
	}
	if e = writeJSON(filepath.Join(j.root, id, "manifest.json"), m); e != nil {
		return e
	}
	return j.save(r)
}
func (j *Jobs) Put(id, owner, path string, offset int64, data string, last bool) error {
	r, e := j.record(id, owner, false)
	if e != nil {
		return e
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.Status.State != "PREPARING" {
		return errors.New("job already started")
	}
	var m Manifest
	b, e := os.ReadFile(filepath.Join(j.root, id, "manifest.json"))
	if e != nil {
		return e
	}
	if e = json.Unmarshal(b, &m); e != nil {
		return e
	}
	var entry *ManifestFile
	for _, f := range m.Files {
		if f.Path == path {
			copy := f
			entry = &copy
		}
	}
	if entry == nil {
		return errors.New("file not listed")
	}
	bytes, e := base64.StdEncoding.DecodeString(data)
	if e != nil {
		return e
	}
	if len(bytes) > 256*1024 || offset < 0 || offset > entry.Bytes-int64(len(bytes)) {
		return errors.New("file size or chunk refused")
	}
	target, e := safePath(filepath.Join(j.root, id, "files"), path)
	if e != nil {
		return e
	}
	if e = os.MkdirAll(filepath.Dir(target), 0700); e != nil {
		return e
	}
	f, e := os.OpenFile(target, os.O_CREATE|os.O_RDWR, 0600)
	if e != nil {
		return e
	}
	defer f.Close()
	s, e := f.Stat()
	if e != nil {
		return e
	}
	if s.Size() != offset {
		return errors.New("chunk offset mismatch")
	}
	if _, e = f.WriteAt(bytes, offset); e != nil {
		return e
	}
	if last {
		if e = verifyFile(target, *entry); e != nil {
			os.Remove(target)
			return e
		}
	}
	return nil
}
func verifyFile(path string, entry ManifestFile) error {
	f, e := os.Open(path)
	if e != nil {
		return e
	}
	defer f.Close()
	sum := sha256.New()
	n, e := io.Copy(sum, f)
	if e != nil {
		return e
	}
	if n != entry.Bytes || hex.EncodeToString(sum.Sum(nil)) != entry.SHA {
		return errors.New("manifest size or hash mismatch")
	}
	return nil
}
func (j *Jobs) Run(id, owner string, argv []string, cwd string, lease int64) (JobStatus, error) {
	r, e := j.record(id, owner, true)
	if e != nil {
		return JobStatus{}, e
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.Status.State != "PREPARING" {
		return r.Status, errors.New("job id already used")
	}
	if len(argv) == 0 || len(argv) > 256 || lease <= 0 || lease > 86400000 {
		return r.Status, errors.New("invalid command or lease")
	}
	base := filepath.Join(j.root, id, "files")
	var m Manifest
	b, readErr := os.ReadFile(filepath.Join(j.root, id, "manifest.json"))
	if readErr == nil {
		if e = json.Unmarshal(b, &m); e != nil {
			return r.Status, e
		}
		for _, f := range m.Files {
			p, e := safePath(base, f.Path)
			if e != nil {
				return r.Status, e
			}
			if e = verifyFile(p, f); e != nil {
				return r.Status, e
			}
		}
	} else if !os.IsNotExist(readErr) {
		return r.Status, readErr
	}
	work := base
	if cwd != "" && cwd != "." {
		work, e = safePath(base, cwd)
		if e != nil {
			return r.Status, e
		}
	}
	if e = os.MkdirAll(work, 0700); e != nil {
		return r.Status, e
	}
	tmp := filepath.Join(j.root, id, "tmp")
	os.MkdirAll(tmp, 0700)
	cmd := exec.Command(argv[0], argv[1:]...)
	cmd.Dir = work
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	cmd.Env = []string{"PATH=" + os.Getenv("PATH"), "HOME=" + os.Getenv("HOME"), "LANG=" + os.Getenv("LANG"), "USER=" + os.Getenv("USER"), "SHELL=" + os.Getenv("SHELL"), "TMPDIR=" + tmp}
	cmd.Stdout = &jobOutput{r}
	cmd.Stderr = &jobOutput{r}
	now := time.Now().UnixMilli()
	r.Status = JobStatus{Job: id, State: "RUNNING", Started: now, Lease: now + lease, Epoch: j.epoch}
	if e = cmd.Start(); e != nil {
		r.Status.State = "FAILED_TO_START"
		r.Status.Ended = &now
		r.Status.Note = e.Error()
		j.save(r)
		return r.Status, e
	}
	r.cmd = cmd
	j.save(r)
	started := r.Status
	go func() {
		e := cmd.Wait()
		r.mu.Lock()
		defer r.mu.Unlock()
		code := cmd.ProcessState.ExitCode()
		r.Status.Exit = &code
		now := time.Now().UnixMilli()
		r.Status.Ended = &now
		if r.Status.State == "RUNNING" {
			r.Status.State = "EXITED"
		}
		if e != nil && r.Status.Note == "" {
			r.Status.Note = e.Error()
		}
		j.save(r)
	}()
	return started, nil
}

type jobOutput struct{ r *jobRecord }

func (w *jobOutput) Write(b []byte) (int, error) {
	w.r.mu.Lock()
	defer w.r.mu.Unlock()
	s := w.r.Status.Output + strings.ToValidUTF8(string(b), "�")
	if len(s) > 64000 {
		s = s[len(s)-64000:]
	}
	w.r.Status.Output = s
	return len(b), nil
}
func (j *Jobs) List(owner string) []JobStatus {
	j.mu.Lock()
	records := make([]*jobRecord, 0, len(j.records))
	for _, r := range j.records {
		records = append(records, r)
	}
	j.mu.Unlock()
	list := []JobStatus{}
	for _, r := range records {
		r.mu.Lock()
		if r.Owner == owner {
			v := r.Status
			if len(v.Output) > 4000 {
				v.Output = v.Output[len(v.Output)-4000:]
			}
			list = append(list, v)
		}
		r.mu.Unlock()
	}
	return list
}
func (j *Jobs) Renew(id, owner string, lease int64) (JobStatus, error) {
	r, e := j.record(id, owner, false)
	if e != nil {
		return JobStatus{}, e
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.Status.State != "RUNNING" || lease <= 0 || lease > 86400000 {
		return r.Status, errors.New("invalid renewal")
	}
	r.Status.Lease = max(r.Status.Lease, time.Now().UnixMilli()+lease)
	return r.Status, j.save(r)
}
func (j *Jobs) End(id, owner, state string) (JobStatus, error) {
	r, e := j.record(id, owner, false)
	if e != nil {
		return JobStatus{}, e
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.Status.State != "RUNNING" {
		return r.Status, nil
	}
	r.Status.State = state
	r.Status.Note = "Stopped: " + state
	now := time.Now().UnixMilli()
	r.Status.Ended = &now
	pid := r.cmd.Process.Pid
	syscall.Kill(-pid, syscall.SIGTERM)
	go func() {
		time.Sleep(2 * time.Second)
		r.mu.Lock()
		defer r.mu.Unlock()
		if r.Status.Exit == nil {
			syscall.Kill(-pid, syscall.SIGKILL)
		}
	}()
	return r.Status, j.save(r)
}
func (j *Jobs) Expire() {
	j.mu.Lock()
	var records []*jobRecord
	for _, r := range j.records {
		records = append(records, r)
	}
	j.mu.Unlock()
	for _, r := range records {
		r.mu.Lock()
		expired := r.Status.State == "RUNNING" && r.Status.Lease < time.Now().UnixMilli()
		id, owner := r.Status.Job, r.Owner
		r.mu.Unlock()
		if expired {
			j.End(id, owner, "LEASE_EXPIRED")
		}
	}
}
func (j *Jobs) Forget(id, owner string) error {
	r, e := j.record(id, owner, false)
	if e != nil {
		return e
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.Status.State == "RUNNING" {
		return errors.New("job is still running")
	}
	j.mu.Lock()
	defer j.mu.Unlock()
	if e = os.RemoveAll(filepath.Join(j.root, id)); e != nil {
		return e
	}
	delete(j.records, id)
	return nil
}
func (j *Jobs) Fetch(id, owner, path string) (string, error) {
	r, e := j.record(id, owner, false)
	if e != nil {
		return "", e
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	p, e := safePath(filepath.Join(j.root, id, "files"), path)
	if e != nil {
		return "", e
	}
	f, e := os.Open(p)
	if e != nil {
		return "", e
	}
	defer f.Close()
	b, e := io.ReadAll(io.LimitReader(f, 16*1024*1024+1))
	if len(b) > 16*1024*1024 {
		return "", fmt.Errorf("file exceeds 16 MB")
	}
	return base64.StdEncoding.EncodeToString(b), e
}
