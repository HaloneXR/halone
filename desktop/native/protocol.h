#pragma once

#include <mach/mach.h>
#include <stdint.h>

#define SPIKE_MAX_SURFACES 8

enum {
    SPIKE_MSG_HELLO = 1,
    SPIKE_MSG_FRAME = 2,
    SPIKE_MSG_TICK = 3,
    SPIKE_MSG_INPUT = 4,
    SPIKE_MSG_RESIZE = 5,
    SPIKE_MSG_CURSOR = 6,
};

#define SPIKE_CURSOR_NAME_BYTES 32

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

enum {
    SPIKE_INPUT_MOVE = 1,
    SPIKE_INPUT_DOWN = 2,
    SPIKE_INPUT_UP = 3,
    SPIKE_INPUT_WHEEL = 4,
    SPIKE_INPUT_LEAVE = 5,
    SPIKE_INPUT_KEY_DOWN = 6,
    SPIKE_INPUT_KEY_UP = 7,
};

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
} spike_producer_inbox;
