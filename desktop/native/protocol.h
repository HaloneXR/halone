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
    SPIKE_MSG_PAGE = 10,
    SPIKE_MSG_COMMAND = 11,
    SPIKE_MSG_POPUP = 12,
    SPIKE_MSG_CONTEXT = 13,
    SPIKE_MSG_DOWNLOAD = 14,
    SPIKE_MSG_PROMPT = 15,
    SPIKE_MSG_FULLSCREEN = 16,
    SPIKE_MSG_SCROLL = 17,
    SPIKE_MSG_DOWNLOAD_CONTROL = 18,
    SPIKE_MSG_PROMPT_ANSWER = 19,
};

/* CONTEXT's flags and media kinds. */
#define SPIKE_CONTEXT_EDITABLE 1u
#define SPIKE_CONTEXT_CAN_CUT 2u
#define SPIKE_CONTEXT_CAN_COPY 4u
#define SPIKE_CONTEXT_CAN_PASTE 8u
enum {
    SPIKE_MEDIA_NONE = 0,
    SPIKE_MEDIA_IMAGE = 1,
    SPIKE_MEDIA_VIDEO = 2,
    SPIKE_MEDIA_AUDIO = 3,
};

/* A DOWNLOAD's state. */
enum {
    SPIKE_DOWNLOAD_STARTED = 1,
    SPIKE_DOWNLOAD_PROGRESS = 2,
    SPIKE_DOWNLOAD_DONE = 3,
    SPIKE_DOWNLOAD_FAILED = 4,
    SPIKE_DOWNLOAD_CANCELLED = 5,
};

/* PROMPT's flags. */
#define SPIKE_PROMPT_REMEMBERABLE 1u
#define SPIKE_PROMPT_GONE 2u

/* OPEN's flags: a private panel keeps nothing, in a profile of its own in memory. */
#define SPIKE_OPEN_PRIVATE 1u

/* PAGE's flags. */
#define SPIKE_PAGE_LOADING 1u
#define SPIKE_PAGE_CAN_GO_BACK 2u
#define SPIKE_PAGE_CAN_GO_FORWARD 4u

/* What a COMMAND asks of a panel's page; NAVIGATE takes the URL in its text. */
enum {
    SPIKE_COMMAND_NAVIGATE = 1,
    SPIKE_COMMAND_BACK = 2,
    SPIKE_COMMAND_FORWARD = 3,
    SPIKE_COMMAND_RELOAD = 4,
    SPIKE_COMMAND_STOP = 5,
    SPIKE_COMMAND_CUT = 6,
    SPIKE_COMMAND_COPY = 7,
    SPIKE_COMMAND_PASTE = 8,
    SPIKE_COMMAND_SELECT_ALL = 9,
    /* Downloads the URL in the text, as a link the page asked for would be. */
    SPIKE_COMMAND_DOWNLOAD = 10,
    /* Scrolls the page by the pixels in the text, up for negative. */
    SPIKE_COMMAND_SCROLL_BY = 11,
};

#define SPIKE_CURSOR_NAME_BYTES 32

/* In an input's modifiers, beside AppKit's flags: a key down the OS repeated while the key is held. */
#define SPIKE_MODIFIER_REPEAT (1u << 30)
#define SPIKE_URL_BYTES 2048
#define SPIKE_ORIGIN_BYTES 256
#define SPIKE_SCRIPT_BYTES 4096
#define SPIKE_PATH_BYTES 1024
#define SPIKE_TITLE_BYTES 512
#define SPIKE_KIND_BYTES 32
#define SPIKE_MIME_BYTES 128

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
    uint32_t flags;
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

/* Where a panel's page is: its title, its URL, and the PAGE flags. Sent on every change. */
typedef struct {
    mach_msg_header_t header;
    uint32_t panel;
    uint32_t flags;
    char title[SPIKE_TITLE_BYTES];
    char url[SPIKE_URL_BYTES];
} spike_page;

typedef struct {
    mach_msg_header_t header;
    uint32_t panel;
    uint32_t kind;
    char text[SPIKE_URL_BYTES];
} spike_command;

/* A panel's page asked for [url] in a new window or tab, which the producer did not open. */
typedef struct {
    mach_msg_header_t header;
    uint32_t panel;
    uint32_t background;
    char url[SPIKE_URL_BYTES];
} spike_popup;

/* The wearer asked for the page's context menu at ([x], [y]), on what it describes; the producer showed none. */
typedef struct {
    mach_msg_header_t header;
    uint32_t panel;
    uint32_t flags;
    uint32_t media;
    float x;
    float y;
    char link[SPIKE_URL_BYTES];
    char source[SPIKE_URL_BYTES];
    char selection[SPIKE_TITLE_BYTES];
} spike_context;

/*
 * A download of a panel's page: STARTED waits for a DOWNLOAD_CONTROL saying
 * where it goes; the rest report it, [path] once it is known.
 */
typedef struct {
    mach_msg_header_t header;
    uint32_t panel;
    uint32_t download;
    uint32_t state;
    uint32_t pad;
    int64_t received;
    int64_t total;
    char url[SPIKE_URL_BYTES];
    char name[SPIKE_TITLE_BYTES];
    char mime[SPIKE_MIME_BYTES];
    char path[SPIKE_PATH_BYTES];
} spike_download;

/* Where download [download] goes, or with [cancel] that it stops; an empty path asks for nothing more. */
typedef struct {
    mach_msg_header_t header;
    uint32_t download;
    uint32_t cancel;
    char path[SPIKE_PATH_BYTES];
} spike_download_control;

/* A permission the page asks for, the protocol's PermissionKind in [kind]; with PROMPT_GONE, one it no longer waits on. */
typedef struct {
    mach_msg_header_t header;
    uint32_t panel;
    uint32_t prompt;
    uint32_t flags;
    char kind[SPIKE_KIND_BYTES];
    char host[SPIKE_ORIGIN_BYTES];
} spike_prompt;

typedef struct {
    mach_msg_header_t header;
    uint32_t panel;
    uint32_t prompt;
    uint32_t allow;
    uint32_t remember;
} spike_prompt_answer;

typedef struct {
    mach_msg_header_t header;
    uint32_t panel;
    uint32_t fullscreen;
} spike_fullscreen;

/* The page's vertical scroll offset, in its pixels. */
typedef struct {
    mach_msg_header_t header;
    uint32_t panel;
    float y;
} spike_scroll;

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
    struct {
        spike_page msg;
        mach_msg_trailer_t trailer;
    } page;
    struct {
        spike_popup msg;
        mach_msg_trailer_t trailer;
    } popup;
    struct {
        spike_context msg;
        mach_msg_trailer_t trailer;
    } context;
    struct {
        spike_download msg;
        mach_msg_trailer_t trailer;
    } download;
    struct {
        spike_prompt msg;
        mach_msg_trailer_t trailer;
    } prompt;
    struct {
        spike_fullscreen msg;
        mach_msg_trailer_t trailer;
    } fullscreen;
    struct {
        spike_scroll msg;
        mach_msg_trailer_t trailer;
    } scroll;
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
    struct {
        spike_command msg;
        mach_msg_trailer_t trailer;
    } command;
    struct {
        spike_download_control msg;
        mach_msg_trailer_t trailer;
    } downloadControl;
    struct {
        spike_prompt_answer msg;
        mach_msg_trailer_t trailer;
    } promptAnswer;
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
    uint32_t flags;
    char url[SPIKE_URL_BYTES];
    char origin[SPIKE_ORIGIN_BYTES];
    char script[SPIKE_SCRIPT_BYTES];
    char root[SPIKE_PATH_BYTES];
} spike_open;

typedef struct {
    uint32_t id;
    uint32_t panel;
} spike_close;

/* Where a panel's page is: its title, its URL, and the PAGE flags. Sent on every change. */
typedef struct {
    uint32_t id;
    uint32_t panel;
    uint32_t flags;
    char title[SPIKE_TITLE_BYTES];
    char url[SPIKE_URL_BYTES];
} spike_page;

typedef struct {
    uint32_t id;
    uint32_t panel;
    uint32_t kind;
    char text[SPIKE_URL_BYTES];
} spike_command;

/* A panel's page asked for [url] in a new window or tab, which the producer did not open. */
typedef struct {
    uint32_t id;
    uint32_t panel;
    uint32_t background;
    char url[SPIKE_URL_BYTES];
} spike_popup;

/* The wearer asked for the page's context menu at ([x], [y]), on what it describes; the producer showed none. */
typedef struct {
    uint32_t id;
    uint32_t panel;
    uint32_t flags;
    uint32_t media;
    float x;
    float y;
    char link[SPIKE_URL_BYTES];
    char source[SPIKE_URL_BYTES];
    char selection[SPIKE_TITLE_BYTES];
} spike_context;

/*
 * A download of a panel's page: STARTED waits for a DOWNLOAD_CONTROL saying
 * where it goes; the rest report it, [path] once it is known.
 */
typedef struct {
    uint32_t id;
    uint32_t panel;
    uint32_t download;
    uint32_t state;
    uint32_t pad;
    int64_t received;
    int64_t total;
    char url[SPIKE_URL_BYTES];
    char name[SPIKE_TITLE_BYTES];
    char mime[SPIKE_MIME_BYTES];
    char path[SPIKE_PATH_BYTES];
} spike_download;

/* Where download [download] goes, or with [cancel] that it stops; an empty path asks for nothing more. */
typedef struct {
    uint32_t id;
    uint32_t download;
    uint32_t cancel;
    char path[SPIKE_PATH_BYTES];
} spike_download_control;

/* A permission the page asks for, the protocol's PermissionKind in [kind]; with PROMPT_GONE, one it no longer waits on. */
typedef struct {
    uint32_t id;
    uint32_t panel;
    uint32_t prompt;
    uint32_t flags;
    char kind[SPIKE_KIND_BYTES];
    char host[SPIKE_ORIGIN_BYTES];
} spike_prompt;

typedef struct {
    uint32_t id;
    uint32_t panel;
    uint32_t prompt;
    uint32_t allow;
    uint32_t remember;
} spike_prompt_answer;

typedef struct {
    uint32_t id;
    uint32_t panel;
    uint32_t fullscreen;
} spike_fullscreen;

/* The page's vertical scroll offset, in its pixels. */
typedef struct {
    uint32_t id;
    uint32_t panel;
    float y;
} spike_scroll;

#endif
