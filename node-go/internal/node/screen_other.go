//go:build !darwin || !cgo

package node

import (
	"errors"
	"image"
)

func nativeReady() bool { return false }
func nativeDetail() string {
	return "Select a loopback VNC backend on Linux; native capture requires macOS 14+"
}
func nativeCapture() (image.Image, error)            { return nil, errors.New(nativeDetail()) }
func nativePointer(x, y int, buttons byte, w, h int) {}
func nativeKey(key uint32, down bool)                {}
