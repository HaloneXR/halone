#pragma once

#include <mach/mach.h>
#include <stdint.h>

#define SPIKE_MAX_SURFACES 8

enum {
    SPIKE_MSG_HELLO = 1,
    SPIKE_MSG_FRAME = 2,
    SPIKE_MSG_TICK = 3,
};

typedef struct {
    mach_msg_header_t header;
    mach_msg_body_t body;
    mach_msg_port_descriptor_t surfaces[SPIKE_MAX_SURFACES];
    uint32_t count;
    uint32_t width;
    uint32_t height;
} spike_hello;

typedef struct {
    mach_msg_header_t header;
    uint32_t index;
    uint64_t seq;
    uint64_t started;
    uint64_t ready;
} spike_frame;

typedef struct {
    mach_msg_header_t header;
    uint64_t target;
    uint64_t sent;
} spike_tick;

typedef struct {
    spike_tick msg;
    mach_msg_trailer_t trailer;
} spike_tick_inbox;

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
} spike_inbox;
