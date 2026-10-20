//go:build darwin && cgo

package node

/*
#cgo LDFLAGS: -framework ApplicationServices -framework CoreGraphics
#include <ApplicationServices/ApplicationServices.h>
static int umbra_accessible(void) { return AXIsProcessTrusted(); }
static void umbra_request(int accessibility) {
 if(accessibility) {
  const void *keys[]={kAXTrustedCheckOptionPrompt}; const void *values[]={kCFBooleanTrue};
  CFDictionaryRef options=CFDictionaryCreate(NULL,keys,values,1,&kCFTypeDictionaryKeyCallBacks,&kCFTypeDictionaryValueCallBacks);
  AXIsProcessTrustedWithOptions(options); CFRelease(options);
 } else { CGRequestScreenCaptureAccess(); }
}
*/
import "C"

func permissions() map[string]bool {
	return map[string]bool{"screenRecording": nativeReady(), "accessibility": C.umbra_accessible() != 0}
}
func requestPermission(accessibility bool) {
	v := 0
	if accessibility {
		v = 1
	}
	C.umbra_request(C.int(v))
}
