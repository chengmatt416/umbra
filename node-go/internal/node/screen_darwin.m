//go:build darwin && cgo

#import <Cocoa/Cocoa.h>
#import <ScreenCaptureKit/ScreenCaptureKit.h>
#import <CoreGraphics/CoreGraphics.h>
#include <stdlib.h>

int umbra_screen_ready(void) { return CGPreflightScreenCaptureAccess(); }
int umbra_capture(unsigned char **pixels, int *width, int *height) {
 @autoreleasepool {
  if (@available(macOS 14.0, *)) {
   if (!CGPreflightScreenCaptureAccess()) return 0;
   dispatch_semaphore_t ready=dispatch_semaphore_create(0);
   __block CGImageRef result=NULL;
   [SCShareableContent getShareableContentExcludingDesktopWindows:NO onScreenWindowsOnly:YES completionHandler:^(SCShareableContent *content,NSError *error) {
    SCDisplay *display=content.displays.firstObject;
    if(error || !display){dispatch_semaphore_signal(ready);return;}
    SCContentFilter *filter=[[SCContentFilter alloc] initWithDisplay:display excludingWindows:@[]];
    SCStreamConfiguration *config=[SCStreamConfiguration new];
    double scale=MIN(1.0,1600.0/display.width);
    config.width=(size_t)(display.width*scale);config.height=(size_t)(display.height*scale);config.showsCursor=YES;
    [SCScreenshotManager captureImageWithFilter:filter configuration:config completionHandler:^(CGImageRef image,NSError *error) {if(image && !error) result=CGImageRetain(image);dispatch_semaphore_signal(ready);}];
   }];
   if(dispatch_semaphore_wait(ready,dispatch_time(DISPATCH_TIME_NOW,8*NSEC_PER_SEC))!=0 || !result)return 0;
   size_t w=CGImageGetWidth(result),h=CGImageGetHeight(result);
   if(w==0 || h==0 || w*h>32000000){CGImageRelease(result);return 0;}
   unsigned char *data=calloc(w*h,4);if(!data){CGImageRelease(result);return 0;}
   CGColorSpaceRef space=CGColorSpaceCreateDeviceRGB();
   CGContextRef ctx=CGBitmapContextCreate(data,w,h,8,w*4,space,kCGImageAlphaPremultipliedLast|kCGBitmapByteOrder32Big);
   if(!ctx){free(data);CGColorSpaceRelease(space);CGImageRelease(result);return 0;}
   CGContextDrawImage(ctx,CGRectMake(0,0,w,h),result);CGContextRelease(ctx);CGColorSpaceRelease(space);CGImageRelease(result);
   *pixels=data;*width=(int)w;*height=(int)h;return 1;
  }
  return 0;
 }
}
void umbra_pointer(int x,int y,int buttons,int width,int height) {
 if(!AXIsProcessTrusted())return;
 static int previous=0;
 CGRect bounds=CGDisplayBounds(CGMainDisplayID());
 CGPoint p=CGPointMake(bounds.origin.x+(double)x*bounds.size.width/width,bounds.origin.y+(double)y*bounds.size.height/height);
 CGEventType type=(buttons&1)?kCGEventLeftMouseDragged:kCGEventMouseMoved;
 if((buttons&1)!=(previous&1)) type=(buttons&1)?kCGEventLeftMouseDown:kCGEventLeftMouseUp;
 CGEventRef e=CGEventCreateMouseEvent(NULL,type,p,kCGMouseButtonLeft);CGEventPost(kCGHIDEventTap,e);CFRelease(e);
 if((buttons&4)!=(previous&4)){e=CGEventCreateMouseEvent(NULL,(buttons&4)?kCGEventRightMouseDown:kCGEventRightMouseUp,p,kCGMouseButtonRight);CGEventPost(kCGHIDEventTap,e);CFRelease(e);}
 if(buttons&24){e=CGEventCreateScrollWheelEvent(NULL,kCGScrollEventUnitLine,1,(buttons&8)?3:-3);CGEventPost(kCGHIDEventTap,e);CFRelease(e);}
 previous=buttons;
}
void umbra_key(unsigned int key,int down) {
 if(!AXIsProcessTrusted())return;
 CGKeyCode code=0;bool special=true;
 switch(key){case 0xff0d:code=36;break;case 0xff08:code=51;break;case 0xff09:code=48;break;case 0xff1b:code=53;break;case 0xff51:code=123;break;case 0xff52:code=126;break;case 0xff53:code=124;break;case 0xff54:code=125;break;case 0xffff:code=117;break;case 0xffe1:code=56;break;case 0xffe3:code=59;break;case 0xffe9:code=58;break;case 0xffeb:code=55;break;default:special=false;}
 CGEventRef e=CGEventCreateKeyboardEvent(NULL,code,down);
 if(!special){uint32_t scalar=(key&0xff000000)==0x01000000?key&0xffffff:key;UniChar text[2];size_t count=1;if(scalar>0xffff){scalar-=0x10000;text[0]=0xd800+(scalar>>10);text[1]=0xdc00+(scalar&1023);count=2;}else{text[0]=(UniChar)scalar;}CGEventKeyboardSetUnicodeString(e,count,text);}
 CGEventPost(kCGHIDEventTap,e);CFRelease(e);
}
