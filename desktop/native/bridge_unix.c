#define _GNU_SOURCE
#include <errno.h>
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>
#include "protocol.h"

/* The compositor's side of the Unix socket, through JNA: one producer connection. */

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
    uint32_t fourcc;
    uint32_t planes;
    uint32_t generation;
    uint32_t pad;
    uint64_t modifier;
    uint64_t seq;
    uint64_t begunNs;
    uint64_t paintedNs;
    uint64_t readyNs;
    uint64_t arrivedNs;
    int32_t fds[SPIKE_MAX_SURFACES];
    uint64_t sizes[SPIKE_MAX_SURFACES];
    uint32_t offsets[SPIKE_MAX_SURFACES * SPIKE_MAX_PLANES];
    uint32_t strides[SPIKE_MAX_SURFACES * SPIKE_MAX_PLANES];
    char name[SPIKE_CURSOR_NAME_BYTES];
} halone_unix_event;

static uint64_t now_ns(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (uint64_t)t.tv_sec * 1000000000ull + (uint64_t)t.tv_nsec;
}

int32_t halone_listen(const char *path) {
    int fd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
    if (fd < 0) return -errno;
    struct sockaddr_un address = {0};
    address.sun_family = AF_UNIX;
    snprintf(address.sun_path, sizeof address.sun_path, "%s", path);
    unlink(path);
    if (bind(fd, (struct sockaddr *)&address, sizeof address) != 0 || listen(fd, 1) != 0) {
        int error = errno;
        close(fd);
        return -error;
    }
    return fd;
}

/* The producer's connection, or -1 if none came within [timeoutMs]. */
int32_t halone_accept(int32_t listener, int32_t timeoutMs) {
    struct pollfd p = {listener, POLLIN, 0};
    if (poll(&p, 1, timeoutMs) <= 0) return -1;
    return accept4(listener, NULL, NULL, SOCK_CLOEXEC);
}

/* 0 with an event (or NONE on timeout), negative once the connection is gone. */
int32_t halone_receive(int32_t connection, halone_unix_event *out, int32_t timeoutMs) {
    memset(out, 0, sizeof *out);
    struct pollfd p = {connection, POLLIN, 0};
    int ready = poll(&p, 1, timeoutMs);
    if (ready == 0) return 0;
    if (ready < 0) return errno == EINTR ? 0 : -errno;

    union {
        uint32_t id;
        spike_hello hello;
        spike_frame frame;
        spike_cursor cursor;
        char bytes[1024];
    } in;
    char control[CMSG_SPACE(sizeof(int) * SPIKE_MAX_SURFACES)];
    struct iovec iov = {&in, sizeof in};
    struct msghdr msg = {0};
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = control;
    msg.msg_controllen = sizeof control;
    ssize_t n = recvmsg(connection, &msg, MSG_CMSG_CLOEXEC);
    if (n <= 0) return n == 0 ? -1 : -errno;
    out->arrivedNs = now_ns();

    int fds[SPIKE_MAX_SURFACES];
    uint32_t fdCount = 0;
    for (struct cmsghdr *c = CMSG_FIRSTHDR(&msg); c; c = CMSG_NXTHDR(&msg, c)) {
        if (c->cmsg_level != SOL_SOCKET || c->cmsg_type != SCM_RIGHTS) continue;
        uint32_t count = (uint32_t)((c->cmsg_len - CMSG_LEN(0)) / sizeof(int));
        for (uint32_t i = 0; i < count; i++) {
            int fd;
            memcpy(&fd, CMSG_DATA(c) + i * sizeof(int), sizeof fd);
            if (fdCount < SPIKE_MAX_SURFACES) fds[fdCount++] = fd;
            else close(fd);
        }
    }

    if (in.id == SPIKE_MSG_HELLO && (size_t)n >= sizeof in.hello) {
        spike_hello *h = &in.hello;
        out->kind = HALONE_EVENT_HELLO;
        out->panel = h->panel;
        out->count = h->count < fdCount ? h->count : fdCount;
        out->width = h->width;
        out->height = h->height;
        out->fourcc = h->fourcc;
        out->planes = h->planes < SPIKE_MAX_PLANES ? h->planes : SPIKE_MAX_PLANES;
        out->generation = h->generation;
        out->modifier = h->modifier;
        for (uint32_t i = 0; i < SPIKE_MAX_SURFACES; i++) {
            out->fds[i] = i < out->count ? fds[i] : -1;
            out->sizes[i] = h->sizes[i];
            for (uint32_t k = 0; k < SPIKE_MAX_PLANES; k++) {
                out->offsets[i * SPIKE_MAX_PLANES + k] = h->layout[i][k].offset;
                out->strides[i * SPIKE_MAX_PLANES + k] = h->layout[i][k].stride;
            }
        }
        for (uint32_t i = out->count; i < fdCount; i++) close(fds[i]);
        return 0;
    }
    for (uint32_t i = 0; i < fdCount; i++) close(fds[i]);
    if (in.id == SPIKE_MSG_FRAME && (size_t)n >= sizeof in.frame) {
        out->kind = HALONE_EVENT_FRAME;
        out->panel = in.frame.panel;
        out->index = in.frame.index;
        out->generation = in.frame.generation;
        out->seq = in.frame.seq;
        out->begunNs = in.frame.begun;
        out->paintedNs = in.frame.painted;
        out->readyNs = in.frame.ready;
    } else if (in.id == SPIKE_MSG_CURSOR && (size_t)n >= sizeof in.cursor) {
        out->kind = HALONE_EVENT_CURSOR;
        out->panel = in.cursor.panel;
        memcpy(out->name, in.cursor.name, SPIKE_CURSOR_NAME_BYTES);
        out->name[SPIKE_CURSOR_NAME_BYTES - 1] = 0;
    }
    return 0;
}

static int32_t send_packet(int32_t connection, const void *data, size_t size) {
    return send(connection, data, size, MSG_NOSIGNAL | MSG_DONTWAIT) == (ssize_t)size ? 0 : -errno;
}

int32_t halone_send_tick(int32_t connection, uint64_t targetNs) {
    spike_tick t = {0};
    t.id = SPIKE_MSG_TICK;
    t.target = targetNs;
    t.sent = now_ns();
    return send_packet(connection, &t, sizeof t);
}

int32_t halone_send_input(int32_t connection, uint32_t panel, uint32_t kind, float x, float y, float dx, float dy,
                          uint32_t button, uint32_t clicks, uint32_t modifiers, uint32_t keyCode, uint32_t character,
                          uint32_t unmodified) {
    spike_input in = {0};
    in.id = SPIKE_MSG_INPUT;
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
    return send_packet(connection, &in, sizeof in);
}

int32_t halone_send_resize(int32_t connection, uint32_t panel, uint32_t width, uint32_t height) {
    spike_resize r = {0};
    r.id = SPIKE_MSG_RESIZE;
    r.panel = panel;
    r.width = width;
    r.height = height;
    return send_packet(connection, &r, sizeof r);
}

int32_t halone_send_release(int32_t connection, uint32_t panel, uint32_t index, uint32_t generation) {
    spike_release r = {0};
    r.id = SPIKE_MSG_RELEASE;
    r.panel = panel;
    r.index = index;
    r.generation = generation;
    return send_packet(connection, &r, sizeof r);
}

static void copy_text(char *to, size_t size, const char *from) {
    if (!from || size == 0) return;
    strncpy(to, from, size - 1);
    to[size - 1] = 0;
}

int32_t halone_send_open(int32_t connection, uint32_t panel, uint32_t width, uint32_t height, const char *url,
                         const char *origin, const char *script) {
    spike_open o;
    memset(&o, 0, sizeof o);
    o.id = SPIKE_MSG_OPEN;
    o.panel = panel;
    o.width = width;
    o.height = height;
    copy_text(o.url, sizeof o.url, url);
    copy_text(o.origin, sizeof o.origin, origin);
    copy_text(o.script, sizeof o.script, script);
    return send_packet(connection, &o, sizeof o);
}

int32_t halone_send_close(int32_t connection, uint32_t panel) {
    spike_close c = {0};
    c.id = SPIKE_MSG_CLOSE;
    c.panel = panel;
    return send_packet(connection, &c, sizeof c);
}

void halone_close(int32_t fd) {
    if (fd >= 0) close(fd);
}
