package node

import (
	"os/exec"
	"runtime"
	"time"
)

func (j *Jobs) Running() bool {
	j.mu.Lock()
	records := make([]*jobRecord, 0, len(j.records))
	for _, r := range j.records {
		records = append(records, r)
	}
	j.mu.Unlock()
	for _, r := range records {
		r.mu.Lock()
		running := r.Status.State == "RUNNING"
		r.mu.Unlock()
		if running {
			return true
		}
	}
	return false
}
func awakeCommand() *exec.Cmd {
	if runtime.GOOS == "darwin" {
		return exec.Command("caffeinate", "-i")
	}
	return exec.Command("systemd-inhibit", "--what=idle:sleep", "--who=Umbra Node", "--why=Remote job running", "--mode=block", "sleep", "infinity")
}
func (s *Server) keepAwake() {
	var cmd *exec.Cmd
	defer func() {
		if cmd != nil {
			cmd.Process.Kill()
			cmd.Wait()
		}
	}()
	tick := time.NewTicker(time.Second)
	defer tick.Stop()
	for {
		select {
		case <-s.stop:
			return
		case <-tick.C:
			active := s.Home.Settings().KeepAwake && s.Jobs.Running()
			if active && cmd == nil {
				candidate := awakeCommand()
				if candidate.Start() == nil {
					cmd = candidate
				}
			} else if !active && cmd != nil {
				cmd.Process.Kill()
				cmd.Wait()
				cmd = nil
			}
		}
	}
}
