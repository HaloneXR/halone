#include <IOSurface/IOSurface.h>
#include <mach/mach.h>
#include <mach/mach_time.h>
#include <servers/bootstrap.h>
#include <stdint.h>
#include <string.h>
#include "protocol.h"

enum {
    HALONE_EVENT_NONE = 0,
    HALONE_EVENT_HELLO = 1,
    HALONE_EVENT_FRAME = 2,
    HALONE_EVENT_CURSOR = 3,
};

typedef struct {
    int32_t kind;
    uint32_t panel;
    uint32_t index;
    uint32_t count;
    uint32_t width;
    uint32_t height;
    uint32_t reply;
    uint32_t pad;
    uint64_t seq;
    uint64_t begunNs;
    uint64_t paintedNs;
    uint64_t readyNs;
    uint64_t arrivedNs;
    uint64_t surfaces[SPIKE_MAX_SURFACES];
    char name[SPIKE_CURSOR_NAME_BYTES];
} halone_event;

static mach_timebase_info_data_t timebase;

static uint64_t to_ns(uint64_t ticks) {
    if (timebase.denom == 0) mach_timebase_info(&timebase);
    return ticks * timebase.numer / timebase.denom;
}

static uint64_t to_ticks(uint64_t ns) {
    if (timebase.denom == 0) mach_timebase_info(&timebase);
    return ns * timebase.denom / timebase.numer;
}

int32_t halone_check_in(const char *name, uint32_t *port) {
    mach_port_t p = MACH_PORT_NULL;
    kern_return_t kr = bootstrap_check_in(bootstrap_port, name, &p);
    *port = p;
    return kr;
}

int32_t halone_receive(uint32_t port, halone_event *out, int32_t timeoutMs) {
    spike_consumer_inbox in;
    memset(&in, 0, sizeof in);
    memset(out, 0, sizeof *out);
    mach_msg_option_t options = MACH_RCV_MSG | (timeoutMs >= 0 ? MACH_RCV_TIMEOUT : 0);
    kern_return_t kr = mach_msg(&in.header, options, 0, sizeof in, port,
                                timeoutMs >= 0 ? (mach_msg_timeout_t)timeoutMs : MACH_MSG_TIMEOUT_NONE, MACH_PORT_NULL);
    if (kr == MACH_RCV_TIMED_OUT) return 0;
    if (kr != KERN_SUCCESS) return kr;
    out->arrivedNs = to_ns(mach_absolute_time());
    if (in.header.msgh_id == SPIKE_MSG_HELLO) {
        spike_hello *h = &in.hello.msg;
        out->kind = HALONE_EVENT_HELLO;
        out->panel = h->panel;
        out->count = h->count < SPIKE_MAX_SURFACES ? h->count : SPIKE_MAX_SURFACES;
        out->width = h->width;
        out->height = h->height;
        out->reply = in.header.msgh_remote_port;
        for (uint32_t i = 0; i < SPIKE_MAX_SURFACES; i++) {
            mach_port_t p = h->surfaces[i].name;
            if (i < out->count && p != MACH_PORT_NULL) out->surfaces[i] = (uint64_t)(uintptr_t)IOSurfaceLookupFromMachPort(p);
            if (p != MACH_PORT_NULL) mach_port_deallocate(mach_task_self(), p);
        }
    } else if (in.header.msgh_id == SPIKE_MSG_FRAME) {
        spike_frame *f = &in.frame.msg;
        out->kind = HALONE_EVENT_FRAME;
        out->panel = f->panel;
        out->index = f->index;
        out->seq = f->seq;
        out->begunNs = f->begun ? to_ns(f->begun) : 0;
        out->paintedNs = to_ns(f->painted);
        out->readyNs = to_ns(f->ready);
    } else if (in.header.msgh_id == SPIKE_MSG_CURSOR) {
        spike_cursor *c = &in.cursor.msg;
        out->kind = HALONE_EVENT_CURSOR;
        out->panel = c->panel;
        memcpy(out->name, c->name, SPIKE_CURSOR_NAME_BYTES);
        out->name[SPIKE_CURSOR_NAME_BYTES - 1] = 0;
    }
    return 0;
}

int32_t halone_send_tick(uint32_t producer, uint64_t targetNs) {
    spike_tick t;
    memset(&t, 0, sizeof t);
    t.header.msgh_bits = MACH_MSGH_BITS_SET(MACH_MSG_TYPE_COPY_SEND, 0, 0, 0);
    t.header.msgh_size = sizeof t;
    t.header.msgh_remote_port = producer;
    t.header.msgh_id = SPIKE_MSG_TICK;
    t.target = to_ticks(targetNs);
    t.sent = mach_absolute_time();
    return mach_msg(&t.header, MACH_SEND_MSG | MACH_SEND_TIMEOUT, sizeof t, 0, MACH_PORT_NULL, 0, MACH_PORT_NULL);
}

int32_t halone_send_input(uint32_t producer, uint32_t panel, uint32_t kind, float x, float y, float dx, float dy,
                          uint32_t button, uint32_t clicks, uint32_t modifiers, uint32_t keyCode, uint32_t character,
                          uint32_t unmodified) {
    spike_input in;
    memset(&in, 0, sizeof in);
    in.header.msgh_bits = MACH_MSGH_BITS_SET(MACH_MSG_TYPE_COPY_SEND, 0, 0, 0);
    in.header.msgh_size = sizeof in;
    in.header.msgh_remote_port = producer;
    in.header.msgh_id = SPIKE_MSG_INPUT;
    in.panel = panel;
    in.kind = kind;
    in.x = x;
    in.y = y;
    in.dx = dx;
    in.dy = dy;
    in.button = button;
    in.clicks = clicks;
    in.modifiers = modifiers;
    in.keyCode = keyCode;
    in.character = (uint16_t)character;
    in.unmodified = (uint16_t)unmodified;
    return mach_msg(&in.header, MACH_SEND_MSG | MACH_SEND_TIMEOUT, sizeof in, 0, MACH_PORT_NULL, 0, MACH_PORT_NULL);
}

int32_t halone_send_resize(uint32_t producer, uint32_t panel, uint32_t width, uint32_t height) {
    spike_resize r;
    memset(&r, 0, sizeof r);
    r.header.msgh_bits = MACH_MSGH_BITS_SET(MACH_MSG_TYPE_COPY_SEND, 0, 0, 0);
    r.header.msgh_size = sizeof r;
    r.header.msgh_remote_port = producer;
    r.header.msgh_id = SPIKE_MSG_RESIZE;
    r.panel = panel;
    r.width = width;
    r.height = height;
    return mach_msg(&r.header, MACH_SEND_MSG | MACH_SEND_TIMEOUT, sizeof r, 0, MACH_PORT_NULL, 0, MACH_PORT_NULL);
}

void halone_surface_use(uint64_t surface, int32_t delta) {
    IOSurfaceRef s = (IOSurfaceRef)(uintptr_t)surface;
    if (!s) return;
    if (delta > 0) IOSurfaceIncrementUseCount(s);
    else IOSurfaceDecrementUseCount(s);
}

void halone_surface_release(uint64_t surface) {
    if (surface) CFRelease((IOSurfaceRef)(uintptr_t)surface);
}

void halone_release_port(uint32_t port) {
    if (port != MACH_PORT_NULL) mach_port_deallocate(mach_task_self(), port);
}
