#import <ApplicationServices/ApplicationServices.h>
#import <Carbon/Carbon.h>
#include <os/lock.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>

typedef struct {
    int32_t keycode;
    int32_t down;
    int32_t repeat;
    int64_t flags;
} Key;

static CFMachPortRef tap;
static volatile int32_t holding;
static os_unfair_lock lock = OS_UNFAIR_LOCK_INIT;
static Key queue[256];
static uint32_t head, tail;
static bool taken[128];

static bool functionRow(int64_t code) {
    switch (code) {
        case kVK_F1: case kVK_F2: case kVK_F3: case kVK_F4: case kVK_F5: case kVK_F6: case kVK_F7: case kVK_F8:
        case kVK_F9: case kVK_F10: case kVK_F11: case kVK_F12: case kVK_F13: case kVK_F14: case kVK_F15: case kVK_F16:
        case kVK_F17: case kVK_F18: case kVK_F19: case kVK_F20:
            return true;
        default:
            return false;
    }
}

static bool forHalone(int64_t code, CGEventFlags flags) {
    bool command = flags & kCGEventFlagMaskCommand;
    if (command && (flags & kCGEventFlagMaskAlternate) && code == kVK_Escape) return false;
    return command || (flags & kCGEventFlagMaskControl) || functionRow(code);
}

static CGEventRef onEvent(CGEventTapProxy proxy, CGEventType type, CGEventRef event, void *info) {
    if (type == kCGEventTapDisabledByTimeout || type == kCGEventTapDisabledByUserInput) {
        CGEventTapEnable(tap, true);
        return event;
    }
    int64_t code = CGEventGetIntegerValueField(event, kCGKeyboardEventKeycode);
    if (code < 0 || code >= 128) return event;
    if (type == kCGEventFlagsChanged) {
        if (code != kVK_Function) return event;
        bool pressed = CGEventGetFlags(event) & kCGEventFlagMaskSecondaryFn;
        bool take = pressed ? holding : taken[code];
        taken[code] = pressed && take;
        return take ? NULL : event;
    }
    bool down = type == kCGEventKeyDown;
    CGEventFlags flags = CGEventGetFlags(event);
    bool take;
    if (down) {
        bool repeat = CGEventGetIntegerValueField(event, kCGKeyboardEventAutorepeat) != 0;
        take = repeat ? taken[code] : holding && forHalone(code, flags);
        taken[code] = take;
    } else {
        take = taken[code];
        taken[code] = false;
    }
    if (!take) return event;
    os_unfair_lock_lock(&lock);
    if (tail - head < 256) {
        queue[tail % 256] = (Key){(int32_t)code, down, down && CGEventGetIntegerValueField(event, kCGKeyboardEventAutorepeat) != 0, (int64_t)flags};
        tail++;
    }
    os_unfair_lock_unlock(&lock);
    return NULL;
}

static void *run(void *source) {
    CFRunLoopAddSource(CFRunLoopGetCurrent(), source, kCFRunLoopCommonModes);
    CGEventTapEnable(tap, true);
    CFRunLoopRun();
    return NULL;
}

int32_t halone_keys_trusted(int32_t prompt) {
    if (!prompt) return AXIsProcessTrusted();
    const void *keys[] = {kAXTrustedCheckOptionPrompt};
    const void *values[] = {kCFBooleanTrue};
    CFDictionaryRef options = CFDictionaryCreate(NULL, keys, values, 1, &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    Boolean trusted = AXIsProcessTrustedWithOptions(options);
    CFRelease(options);
    return trusted;
}

int32_t halone_keys_start(void) {
    if (tap) return 0;
    CGEventMask mask = CGEventMaskBit(kCGEventKeyDown) | CGEventMaskBit(kCGEventKeyUp) | CGEventMaskBit(kCGEventFlagsChanged);
    tap = CGEventTapCreate(kCGHIDEventTap, kCGHeadInsertEventTap, kCGEventTapOptionDefault, mask, onEvent, NULL);
    if (!tap) return -1;
    CFRunLoopSourceRef source = CFMachPortCreateRunLoopSource(NULL, tap, 0);
    pthread_t thread;
    pthread_create(&thread, NULL, run, (void *)source);
    pthread_detach(thread);
    return 0;
}

void halone_keys_hold(int32_t on) { holding = on; }

int32_t halone_keys_secure(void) { return IsSecureEventInputEnabled(); }

int32_t halone_keys_next(int32_t *keycode, int32_t *down, int32_t *repeat, int64_t *flags) {
    os_unfair_lock_lock(&lock);
    bool any = head != tail;
    if (any) {
        Key key = queue[head % 256];
        head++;
        *keycode = key.keycode;
        *down = key.down;
        *repeat = key.repeat;
        *flags = key.flags;
    }
    os_unfair_lock_unlock(&lock);
    return any;
}
