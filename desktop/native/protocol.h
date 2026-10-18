#pragma once

#include <stdint.h>

#define SPIKE_MAX_SURFACES 8

/* The panel of the HELLO a producer sends first, carrying no surfaces, only its port. */
#define SPIKE_NO_PANEL 0xFFFFFFFFu

enum {
    SPIKE_MSG_HELLO = 1,
    SPIKE_MSG_FRAME = 2,
    SPIKE_MSG_TICK = 3,
    SPIKE_MSG_INPUT = 4,
    SPIKE_MSG_RESIZE = 5,
    SPIKE_MSG_CURSOR = 6,
    SPIKE_MSG_RELEASE = 7,
    SPIKE_MSG_OPEN = 8,
    SPIKE_MSG_CLOSE = 9,
};

#define SPIKE_CURSOR_NAME_BYTES 32
#define SPIKE_URL_BYTES 2048
#define SPIKE_ORIGIN_BYTES 256
#define SPIKE_SCRIPT_BYTES 4096
#define SPIKE_PATH_BYTES 1024

enum {
    SPIKE_INPUT_MOVE = 1,
    SPIKE_INPUT_DOWN = 2,
    SPIKE_INPUT_UP = 3,
    SPIKE_INPUT_WHEEL = 4,
    SPIKE_INPUT_LEAVE = 5,
    SPIKE_INPUT_KEY_DOWN = 6,
    SPIKE_INPUT_KEY_UP = 7,
    /* Keys now go to this panel: it takes the keyboard focus. */
    SPIKE_INPUT_FOCUS = 8,
};

#ifdef __APPLE__

#include <mach/mach.h>

typedef struct {
    mach_msg_header_t header;
    mach_msg_body_t body;
    mach_msg_port_descriptor_t surfaces[SPIKE_MAX_SURFACES];
    uint32_t panel;
    uint32_t count;
    uint32_t width;
    uint32_t height;
} spike_hello;

typedef struct {
    mach_msg_header_t header;
    uint32_t panel;
    uint32_t index;
    uint64_t seq;
    uint64_t begun;
    uint64_t painted;
    uint64_t ready;
} spike_frame;

typedef struct {
    mach_msg_header_t header;
    uint64_t target;
    uint64_t sent;
} spike_tick;

typedef struct {
    mach_msg_header_t header;
    uint32_t panel;
    uint32_t kind;
    float x;
    float y;
    float dx;
    float dy;
    uint32_t button;
    uint32_t clicks;
    uint32_t modifiers;
    uint32_t keyCode;
    uint16_t character;
    uint16_t unmodified;
} spike_input;

/* The panel's page is to be laid out at a new size; a HELLO with surfaces of that size answers it. */
typedef struct {
    mach_msg_header_t header;
    uint32_t panel;
    uint32_t width;
    uint32_t height;
} spike_resize;

/*
 * A new panel showing [url]; a HELLO with its surfaces answers it. After each
 * load of a page on [origin], [script] runs in it; an empty script runs nothing.
 * A [root] serves [origin] from that folder, as https://halone.local/ is served.
 */
typedef struct {
    mach_msg_header_t header;
    uint32_t panel;
    uint32_t width;
    uint32_t height;
    char url[SPIKE_URL_BYTES];
    char origin[SPIKE_ORIGIN_BYTES];
    char script[SPIKE_SCRIPT_BYTES];
    char root[SPIKE_PATH_BYTES];
} spike_open;

typedef struct {
    mach_msg_header_t header;
    uint32_t panel;
} spike_close;

/* The cursor the panel's content asks for, by its CSS name. */
typedef struct {
    mach_msg_header_t header;
    uint32_t panel;
    char name[SPIKE_CURSOR_NAME_BYTES];
} spike_cursor;

typedef union {
    mach_msg_header_t header;
    struct {
        spike_hello msg;
        mach_msg_trailer_t trailer;
    } hello;
    struct {
        spike_frame msg;
        mach_msg_trailer_t trailer;
    } frame;
    struct {
        spike_cursor msg;
        mach_msg_trailer_t trailer;
    } cursor;
} spike_consumer_inbox;

typedef union {
    mach_msg_header_t header;
    struct {
        spike_tick msg;
        mach_msg_trailer_t trailer;
    } tick;
    struct {
        spike_input msg;
        mach_msg_trailer_t trailer;
    } input;
    struct {
        spike_resize msg;
        mach_msg_trailer_t trailer;
    } resize;
    struct {
        spike_open msg;
        mach_msg_trailer_t trailer;
    } open;
    struct {
        spike_close msg;
        mach_msg_trailer_t trailer;
    } close;
} spike_producer_inbox;

#else

/*
 * Elsewhere the same messages travel over a SOCK_SEQPACKET Unix socket, one
 * message per packet, each starting with its id. Surfaces are DMA-BUFs: a HELLO
 * carries one fd per surface (SCM_RIGHTS, in order) and every plane's layout.
 * The consumer sends RELEASE for each FRAME once it no longer reads that
 * surface; the producer only writes surfaces that are released. A resize
 * starts a new generation of surfaces, and releases name theirs. Times are
 * CLOCK_MONOTONIC nanoseconds.
 */

#define SPIKE_MAX_PLANES 4

typedef struct {
    uint32_t offset;
    uint32_t stride;
} spike_plane;

typedef struct {
    uint32_t id;
    uint32_t panel;
    uint32_t count;
    uint32_t width;
    uint32_t height;
    uint32_t fourcc;
    uint32_t planes;
    uint32_t generation;
    uint64_t modifier;
    uint64_t sizes[SPIKE_MAX_SURFACES];
    spike_plane layout[SPIKE_MAX_SURFACES][SPIKE_MAX_PLANES];
} spike_hello;

typedef struct {
    uint32_t id;
    uint32_t panel;
    uint32_t index;
    uint32_t generation;
    uint64_t seq;
    uint64_t begun;
    uint64_t painted;
    uint64_t ready;
} spike_frame;

typedef struct {
    uint32_t id;
    uint32_t pad;
    uint64_t target;
    uint64_t sent;
} spike_tick;

typedef struct {
    uint32_t id;
    uint32_t panel;
    uint32_t kind;
    float x;
    float y;
    float dx;
    float dy;
    uint32_t button;
    uint32_t clicks;
    uint32_t modifiers;
    uint32_t keyCode;
    uint16_t character;
    uint16_t unmodified;
} spike_input;

typedef struct {
    uint32_t id;
    uint32_t panel;
    uint32_t width;
    uint32_t height;
} spike_resize;

typedef struct {
    uint32_t id;
    uint32_t panel;
    char name[SPIKE_CURSOR_NAME_BYTES];
} spike_cursor;

typedef struct {
    uint32_t id;
    uint32_t panel;
    uint32_t index;
    uint32_t generation;
} spike_release;

typedef struct {
    uint32_t id;
    uint32_t panel;
    uint32_t width;
    uint32_t height;
    char url[SPIKE_URL_BYTES];
    char origin[SPIKE_ORIGIN_BYTES];
    char script[SPIKE_SCRIPT_BYTES];
    char root[SPIKE_PATH_BYTES];
} spike_open;

typedef struct {
    uint32_t id;
    uint32_t panel;
} spike_close;

#endif
