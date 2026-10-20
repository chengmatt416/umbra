//go:build !darwin || !cgo

package node

func permissions() map[string]bool         { return map[string]bool{} }
func requestPermission(accessibility bool) {}
