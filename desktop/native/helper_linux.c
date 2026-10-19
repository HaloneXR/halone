/*
 * halone-helper: what Halone needs root for on Linux, started by the compositor
 * through pkexec and talking on stdin/stdout, one line at a time.
 *
 * In:  "grab" / "release"   take every keyboard (EVIOCGRAB) while Halone has focus, or give them back
 *      "alive"              the compositor's heartbeat; without one for HEARTBEAT_MS the keyboards go back
 *      "edid on" / "off"    patch supported glasses whose EDID lacks the non-desktop marker, or undo it
 * Out: "ready", "key <evdev code> <0|1|2> <CLOCK_MONOTONIC µs>", "edid applied|reverted <connector>", "error <text>"
 *
 * When stdin closes (the compositor quit or died) or on SIGTERM, the keyboards are
 * released and every patched EDID reverted before it exits.
 */
#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <poll.h>
#include <pthread.h>
#include <stdatomic.h>
#include <sys/inotify.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define MAX_KEYBOARDS 16
#define MAX_PATCHED 4
#define HEARTBEAT_MS 1500
#define SCAN_MS 1000
#define UNPLUGGED_SCANS 2

static volatile sig_atomic_t stopping = 0;

static uint64_t now_ms(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (uint64_t)t.tv_sec * 1000 + (uint64_t)t.tv_nsec / 1000000;
}

static void say(const char *format, ...) {
    char line[256];
    va_list args;
    va_start(args, format);
    int n = vsnprintf(line, sizeof line - 1, format, args);
    va_end(args);
    if (n < 0) return;
    if (n > (int)sizeof line - 2) n = sizeof line - 2;
    line[n++] = '\n';
    if (write(STDOUT_FILENO, line, (size_t)n) < 0) stopping = 1;
}

static int read_file(const char *path, unsigned char *out, int size) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    int n = (int)read(fd, out, (size_t)size);
    close(fd);
    return n;
}

static int write_file(const char *path, const void *data, size_t size) {
    int fd = open(path, O_WRONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    ssize_t n = write(fd, data, size);
    close(fd);
    return n == (ssize_t)size ? 0 : -1;
}

/* ---- keyboards ---- */

static int keyboards[MAX_KEYBOARDS];
static int keyboard_count = 0;
static int grabbed = 0;
static const char *only_prefix = NULL; /* --only=<name prefix>: for testing, take only devices so named */

static int wanted(int fd) {
    if (!only_prefix) return 1;
    char name[256] = "";
    ioctl(fd, EVIOCGNAME(sizeof name), name);
    return strncmp(name, only_prefix, strlen(only_prefix)) == 0;
}

static int is_keyboard(int fd) {
    unsigned long bits[(KEY_MAX + 1) / (8 * sizeof(unsigned long)) + 1] = {0};
    if (ioctl(fd, EVIOCGBIT(EV_KEY, sizeof bits), bits) < 0) return 0;
    int per = 8 * sizeof(unsigned long);
    int has_a = (bits[KEY_A / per] >> (KEY_A % per)) & 1;
    int has_z = (bits[KEY_Z / per] >> (KEY_Z % per)) & 1;
    int has_enter = (bits[KEY_ENTER / per] >> (KEY_ENTER % per)) & 1;
    return has_a && has_z && has_enter;
}

static void release_keyboards(void) {
    for (int i = 0; i < keyboard_count; i++) {
        ioctl(keyboards[i], EVIOCGRAB, 0);
        close(keyboards[i]);
    }
    keyboard_count = 0;
    grabbed = 0;
}

/* Takes /dev/input/<name> if it is a keyboard not yet held. */
static void grab_one(const char *name) {
    if (strncmp(name, "event", 5) != 0 || keyboard_count >= MAX_KEYBOARDS) return;
    char path[300];
    snprintf(path, sizeof path, "/dev/input/%s", name);
    int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return;
    struct stat st;
    int known = 0;
    if (fstat(fd, &st) == 0) {
        for (int i = 0; i < keyboard_count; i++) {
            struct stat other;
            if (fstat(keyboards[i], &other) == 0 && other.st_rdev == st.st_rdev) known = 1;
        }
    }
    if (known || !is_keyboard(fd) || !wanted(fd) || ioctl(fd, EVIOCGRAB, 1) < 0) {
        close(fd);
        return;
    }
    int clock = CLOCK_MONOTONIC;
    ioctl(fd, EVIOCSCLOCKID, &clock);
    keyboards[keyboard_count++] = fd;
}

/*
 * Takes every keyboard. Opening an input device can wake a suspended USB device and take
 * a hundred milliseconds or more, so this runs only when the grab starts; keyboards plugged
 * in later come through the /dev/input watch instead of a rescan.
 */
static void grab_keyboards(void) {
    DIR *dir = opendir("/dev/input");
    if (!dir) return;
    struct dirent *entry;
    while ((entry = readdir(dir))) grab_one(entry->d_name);
    closedir(dir);
    grabbed = 1;
}

static void read_keyboard(int index) {
    struct input_event events[64];
    for (;;) {
        ssize_t n = read(keyboards[index], events, sizeof events);
        if (n <= 0) {
            if (n < 0 && errno == ENODEV) {
                close(keyboards[index]);
                keyboards[index] = keyboards[--keyboard_count];
            }
            return;
        }
        for (size_t i = 0; i < (size_t)n / sizeof events[0]; i++)
            if (events[i].type == EV_KEY)
                say("key %u %d %lld", events[i].code, events[i].value,
                    (long long)events[i].input_event_sec * 1000000LL + (long long)events[i].input_event_usec);
    }
}

/* ---- EDID ---- */

static const unsigned char MICROSOFT_OUI[3] = {0x5C, 0x12, 0xCA};

typedef struct {
    char name[64];       /* card1-DP-2 */
    int unplugged_scans;
} patched_t;

/* The EDID side runs on its own thread: reading a DP connector's status makes the kernel
 * probe the display over AUX, which can take a few hundred milliseconds, and keys must not wait. */
static patched_t patched[MAX_PATCHED];
static int patched_count = 0;

/*
 * Patches made, by the glasses' identity (EDID bytes 8-17: maker, product, serial):
 * glasses that come back already in 3D no longer list the 2D mode to build from.
 */
typedef struct {
    unsigned char identity[10];
    unsigned char edid[256];
} made_t;

static made_t made[MAX_PATCHED];
static int made_count = 0;

static const unsigned char *made_for(const unsigned char *edid) {
    for (int i = 0; i < made_count; i++)
        if (memcmp(made[i].identity, edid + 8, 10) == 0) return made[i].edid;
    return NULL;
}

static void remember(const unsigned char *edid, const unsigned char *leasable) {
    if (made_for(edid)) return;
    made_t *m = &made[made_count < MAX_PATCHED ? made_count++ : MAX_PATCHED - 1];
    memcpy(m->identity, edid + 8, 10);
    memcpy(m->edid, leasable, 256);
}
static atomic_int edid_wanted = 0;
static char edid_failed[64] = "";

static void manufacturer(const unsigned char *edid, char out[4]) {
    unsigned word = (unsigned)edid[8] << 8 | edid[9];
    out[0] = (char)(((word >> 10) & 31) + 64);
    out[1] = (char)(((word >> 5) & 31) + 64);
    out[2] = (char)((word & 31) + 64);
    out[3] = 0;
}

static int has_microsoft_block(const unsigned char *edid, int size) {
    for (int i = 128; i + 3 <= size; i++)
        if (memcmp(edid + i, MICROSOFT_OUI, 3) == 0) return 1;
    return 0;
}

static void dtd_size(const unsigned char *b, int *width, int *height, double *hz) {
    int clock = (b[0] | b[1] << 8) * 10000;
    int ha = b[2] | (b[4] & 0xF0) << 4, hb = b[3] | (b[4] & 0x0F) << 8;
    int va = b[5] | (b[7] & 0xF0) << 4, vb = b[6] | (b[7] & 0x0F) << 8;
    *width = ha;
    *height = va;
    *hz = (ha + hb) && (va + vb) ? (double)clock / ((ha + hb) * (va + vb)) : 0;
}

static void checksum(unsigned char *block) {
    unsigned sum = 0;
    for (int i = 0; i < 127; i++) sum += block[i];
    block[127] = (unsigned char)(-sum & 0xFF);
}

/*
 * The glasses' 2D EDID plus a CTA extension holding a Microsoft HMD block (version 2,
 * which makes the kernel set non-desktop) and their full-SBS mode: the 1920x1080@60
 * timing with clock, horizontal timings and image width doubled, as the Air 2 Pro
 * reports it in 3D. As tools/glasses-lease-edid.py builds it.
 */
static int leasable_edid(const unsigned char *current, unsigned char out[256]) {
    memcpy(out, current, 128);
    const unsigned char *sixty = NULL;
    for (int o = 54; o <= 108; o += 18) {
        if (!(out[o] | out[o + 1])) continue;
        int w, h;
        double hz;
        dtd_size(out + o, &w, &h, &hz);
        if (w == 1920 && h == 1080 && hz > 59.5 && hz < 60.5) sixty = out + o;
    }
    if (!sixty) return -1;
    out[126] = 1;
    checksum(out);

    unsigned char *ext = out + 128;
    memset(ext, 0, 128);
    ext[0] = 0x02;
    ext[1] = 0x03;
    unsigned char vsdb[22] = {3 << 5 | 21, 0x5C, 0x12, 0xCA, 0x02, 0x02, 0x00};
    ext[2] = 4 + sizeof vsdb;
    memcpy(ext + 4, vsdb, sizeof vsdb);
    unsigned char *d = ext + 4 + sizeof vsdb;
    memcpy(d, sixty, 18);
    int clock = (sixty[0] | sixty[1] << 8) * 2;
    int h_active = (sixty[2] | (sixty[4] & 0xF0) << 4) * 2;
    int h_blank = (sixty[3] | (sixty[4] & 0x0F) << 8) * 2;
    int h_offset = (sixty[8] | (sixty[11] & 0xC0) << 2) * 2;
    int h_width = (sixty[9] | (sixty[11] & 0x30) << 4) * 2;
    int h_image = (sixty[12] | (sixty[14] & 0xF0) << 4) * 2;
    d[0] = (unsigned char)(clock & 0xFF);
    d[1] = (unsigned char)(clock >> 8);
    d[2] = (unsigned char)(h_active & 0xFF);
    d[3] = (unsigned char)(h_blank & 0xFF);
    d[4] = (unsigned char)((h_active >> 8) << 4 | (h_blank >> 8));
    d[8] = (unsigned char)(h_offset & 0xFF);
    d[9] = (unsigned char)(h_width & 0xFF);
    d[11] = (unsigned char)((h_offset >> 8) << 6 | (h_width >> 8) << 4 | (sixty[11] & 0x0F));
    d[12] = (unsigned char)(h_image & 0xFF);
    d[14] = (unsigned char)((h_image >> 8) << 4 | (sixty[14] & 0x0F));
    checksum(ext);
    return 0;
}

static void connector_path(const char *name, const char *file, char *out, size_t size) {
    snprintf(out, size, "/sys/class/drm/%s/%s", name, file);
}

static void debug_path(const char *name, const char *file, char *out, size_t size) {
    const char *dash = strchr(name, '-');
    snprintf(out, size, "/sys/kernel/debug/dri/%.*s/%s/%s", (int)(dash - name - 4), name + 4, dash + 1, file);
}

static int connected(const char *name) {
    char path[256];
    unsigned char status[32] = {0};
    connector_path(name, "status", path, sizeof path);
    return read_file(path, status, sizeof status - 1) > 0 && strncmp((char *)status, "connected", 9) == 0;
}

/* Unplugs and replugs the connector in software, the only way KWin re-reads non-desktop. */
static void replug(const char *name) {
    char path[256];
    debug_path(name, "trigger_hotplug", path, sizeof path);
    if (write_file(path, "0\n", 2) < 0) return;
    sleep(2);
    for (int i = 0; i < 5; i++) {
        write_file(path, "1\n", 2);
        sleep(2);
        if (connected(name)) return;
    }
}

static void revert(int index, int plugged) {
    char path[256];
    debug_path(patched[index].name, "edid_override", path, sizeof path);
    write_file(path, "reset", 5);
    if (plugged) replug(patched[index].name);
    say("edid reverted %s", strchr(patched[index].name, '-') + 1);
    patched[index] = patched[--patched_count];
}

static void scan_edid(void) {
    for (int i = patched_count - 1; i >= 0; i--) {
        patched[i].unplugged_scans = connected(patched[i].name) ? 0 : patched[i].unplugged_scans + 1;
        if (patched[i].unplugged_scans >= UNPLUGGED_SCANS) revert(i, 0);
    }
    if (!edid_wanted || patched_count >= MAX_PATCHED) return;
    DIR *dir = opendir("/sys/class/drm");
    if (!dir) return;
    struct dirent *entry;
    while ((entry = readdir(dir))) {
        const char *name = entry->d_name;
        if (strncmp(name, "card", 4) != 0 || !strchr(name, '-') || strlen(name) >= sizeof patched[0].name) continue;
        int known = 0;
        for (int i = 0; i < patched_count; i++) known |= strcmp(patched[i].name, name) == 0;
        if (known || !connected(name)) continue;
        char path[256];
        unsigned char edid[512];
        connector_path(name, "edid", path, sizeof path);
        int size = read_file(path, edid, sizeof edid);
        if (size < 128) continue;
        char maker[4];
        manufacturer(edid, maker);
        if (strcmp(maker, "MRG") != 0 || has_microsoft_block(edid, size)) continue;
        unsigned char leasable[256];
        if (leasable_edid(edid, leasable) == 0) {
            remember(edid, leasable);
        } else if (made_for(edid)) {
            memcpy(leasable, made_for(edid), sizeof leasable);
        } else {
            if (strcmp(edid_failed, name) != 0) say("error %s has no 1920x1080 @60 mode to patch; its glasses may be in 3D", name);
            snprintf(edid_failed, sizeof edid_failed, "%s", name);
            continue;
        }
        debug_path(name, "edid_override", path, sizeof path);
        if (write_file(path, leasable, sizeof leasable) < 0) {
            say("error could not override %s: %s", name, strerror(errno));
            continue;
        }
        snprintf(patched[patched_count].name, sizeof patched[0].name, "%s", name);
        patched[patched_count++].unplugged_scans = 0;
        edid_failed[0] = 0;
        replug(name);
        say("edid applied %s", strchr(name, '-') + 1);
        if (patched_count >= MAX_PATCHED) break;
    }
    closedir(dir);
}

static void revert_all(void) {
    while (patched_count > 0) revert(patched_count - 1, connected(patched[patched_count - 1].name));
}

static void *edid_thread(void *unused) {
    (void)unused;
    int was_wanted = 0;
    while (!stopping) {
        int wanted_now = atomic_load(&edid_wanted);
        if (was_wanted && !wanted_now) revert_all();
        was_wanted = wanted_now;
        scan_edid();
        for (int i = 0; i < SCAN_MS / 50 && !stopping && atomic_load(&edid_wanted) == was_wanted; i++) usleep(50 * 1000);
    }
    revert_all();
    return NULL;
}

/* ---- main loop ---- */

static void on_signal(int signal) {
    (void)signal;
    stopping = 1;
}

static void command(const char *line, uint64_t *alive) {
    if (strcmp(line, "alive") == 0) *alive = now_ms();
    else if (strcmp(line, "grab") == 0) {
        *alive = now_ms();
        grab_keyboards();
    } else if (strcmp(line, "release") == 0) release_keyboards();
    else if (strcmp(line, "edid on") == 0) atomic_store(&edid_wanted, 1);
    else if (strcmp(line, "edid off") == 0) atomic_store(&edid_wanted, 0);
}

int main(int argc, char **argv) {
    if (argc > 1 && strncmp(argv[1], "--only=", 7) == 0) only_prefix = argv[1] + 7;
    if (geteuid() != 0) {
        fprintf(stderr, "halone-helper must run as root (through pkexec)\n");
        return 1;
    }
    struct sigaction action = {0};
    action.sa_handler = on_signal;
    sigaction(SIGTERM, &action, NULL);
    sigaction(SIGHUP, &action, NULL);
    sigaction(SIGINT, &action, NULL);
    signal(SIGPIPE, SIG_IGN);

    pthread_t edid;
    pthread_create(&edid, NULL, edid_thread, NULL);
    int watch = inotify_init1(IN_NONBLOCK | IN_CLOEXEC);
    if (watch >= 0) inotify_add_watch(watch, "/dev/input", IN_CREATE | IN_ATTRIB);
    say("ready");
    char buffer[1024];
    size_t used = 0;
    uint64_t alive = now_ms();
    while (!stopping) {
        struct pollfd fds[2 + MAX_KEYBOARDS];
        fds[0].fd = STDIN_FILENO;
        fds[0].events = POLLIN;
        fds[1].fd = watch;
        fds[1].events = POLLIN;
        for (int i = 0; i < keyboard_count; i++) {
            fds[2 + i].fd = keyboards[i];
            fds[2 + i].events = POLLIN;
        }
        int count = 2 + keyboard_count;
        if (poll(fds, (nfds_t)count, 200) < 0 && errno != EINTR) break;

        if (fds[0].revents & (POLLIN | POLLHUP)) {
            ssize_t n = read(STDIN_FILENO, buffer + used, sizeof buffer - 1 - used);
            if (n <= 0) break;
            used += (size_t)n;
            char *start = buffer, *end;
            while ((end = memchr(start, '\n', used - (size_t)(start - buffer)))) {
                *end = 0;
                command(start, &alive);
                start = end + 1;
            }
            used -= (size_t)(start - buffer);
            memmove(buffer, start, used);
            if (used == sizeof buffer - 1) used = 0;
        }
        if (fds[1].revents & POLLIN) {
            char events[4096] __attribute__((aligned(__alignof__(struct inotify_event))));
            ssize_t n;
            while ((n = read(watch, events, sizeof events)) > 0) {
                for (char *at = events; at < events + n;) {
                    struct inotify_event *event = (struct inotify_event *)at;
                    if (grabbed && event->len) grab_one(event->name);
                    at += sizeof *event + event->len;
                }
            }
        }
        for (int i = count - 1; i >= 2; i--)
            if (i - 2 < keyboard_count && (fds[i].revents & (POLLIN | POLLERR | POLLHUP))) read_keyboard(i - 2);

        uint64_t now = now_ms();
        if (grabbed && now - alive > HEARTBEAT_MS) {
            release_keyboards();
            say("released heartbeat");
        }
    }
    release_keyboards();
    stopping = 1;
    pthread_join(edid, NULL);
    return 0;
}
