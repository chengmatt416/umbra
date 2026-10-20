//go:build darwin && cgo

package node

/*
#cgo CFLAGS: -x objective-c -fblocks
#cgo LDFLAGS: -framework Cocoa -framework ScreenCaptureKit -framework CoreGraphics -framework UniformTypeIdentifiers
#include <stdlib.h>
int umbra_screen_ready(void);
int umbra_capture(unsigned char **pixels, int *width, int *height);
void umbra_pointer(int x, int y, int buttons, int width, int height);
void umbra_key(unsigned int key, int down);
*/
import "C"
import (
	"errors"
	"image"
	"unsafe"
)

func nativeReady() bool { return C.umbra_screen_ready() != 0 }
func nativeDetail() string {
	if nativeReady() {
		return "ScreenCaptureKit · captures only while a phone is connected"
	}
	return "Allow Screen Recording in System Settings. Accessibility is needed for pointer and keyboard control."
}
func nativeCapture() (image.Image, error) {
	var data *C.uchar
	var w, h C.int
	if C.umbra_capture(&data, &w, &h) == 0 {
		return nil, errors.New("Screen capture failed; allow Screen Recording and unlock the desktop")
	}
	defer C.free(unsafe.Pointer(data))
	n := int(w) * int(h) * 4
	if n <= 0 || n > 128*1024*1024 {
		return nil, errors.New("screen is too large")
	}
	return &image.RGBA{Pix: C.GoBytes(unsafe.Pointer(data), C.int(n)), Stride: int(w) * 4, Rect: image.Rect(0, 0, int(w), int(h))}, nil
}
func nativePointer(x, y int, buttons byte, w, h int) {
	C.umbra_pointer(C.int(x), C.int(y), C.int(buttons), C.int(w), C.int(h))
}
func nativeKey(key uint32, down bool) {
	d := 0
	if down {
		d = 1
	}
	C.umbra_key(C.uint(key), C.int(d))
}
