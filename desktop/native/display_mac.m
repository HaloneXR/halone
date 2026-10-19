#import <AppKit/AppKit.h>
#import <CoreGraphics/CoreGraphics.h>
#include <stdint.h>

static CGRect othersBounds(CGDirectDisplayID display) {
    CGDirectDisplayID ids[32];
    uint32_t count = 0;
    CGGetActiveDisplayList(32, ids, &count);
    CGRect bounds = CGRectNull;
    for (uint32_t i = 0; i < count; i++) {
        if (ids[i] != display && !CGDisplayIsInMirrorSet(ids[i])) bounds = CGRectUnion(bounds, CGDisplayBounds(ids[i]));
    }
    return bounds;
}

int32_t halone_display_origin(uint32_t display, int32_t *x, int32_t *y) {
    CGRect bounds = CGDisplayBounds(display);
    if (CGRectIsEmpty(bounds)) return -1;
    *x = (int32_t)bounds.origin.x;
    *y = (int32_t)bounds.origin.y;
    return 0;
}

int32_t halone_display_move(uint32_t display, int32_t x, int32_t y) {
    CGDisplayConfigRef config;
    if (CGBeginDisplayConfiguration(&config) != kCGErrorSuccess) return -1;
    CGConfigureDisplayOrigin(config, display, x, y);
    return CGCompleteDisplayConfiguration(config, kCGConfigureForSession) == kCGErrorSuccess ? 0 : -1;
}

int32_t halone_display_corner(uint32_t display, int32_t *x, int32_t *y) {
    CGRect others = othersBounds(display);
    if (CGRectIsNull(others)) return -1;
    *x = (int32_t)CGRectGetMaxX(others);
    *y = (int32_t)CGRectGetMaxY(others);
    return 0;
}

int32_t halone_display_beside(uint32_t display, int32_t *x, int32_t *y) {
    CGRect others = othersBounds(display);
    if (CGRectIsNull(others)) return -1;
    *x = (int32_t)CGRectGetMaxX(others);
    *y = (int32_t)CGRectGetMinY(others);
    return 0;
}

void halone_cursor_to_main(void) {
    CGRect main = CGDisplayBounds(CGMainDisplayID());
    CGWarpMouseCursorPosition(CGPointMake(CGRectGetMidX(main), CGRectGetMidY(main)));
}

int32_t halone_window_overscan(void *window, uint32_t display, int32_t margin) {
    NSWindow *w = (__bridge NSWindow *)window;
    for (NSScreen *screen in NSScreen.screens) {
        if ([screen.deviceDescription[@"NSScreenNumber"] unsignedIntValue] != display) continue;
        [w setFrame:NSInsetRect(screen.frame, -margin, -margin) display:YES];
        return 0;
    }
    return -1;
}
