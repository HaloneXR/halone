#define GL_SILENCE_DEPRECATION
#import <Cocoa/Cocoa.h>
#import <IOSurface/IOSurface.h>
#import <OpenGL/gl3.h>
#import <OpenGL/CGLIOSurface.h>
#import <QuartzCore/QuartzCore.h>
#include <mach/mach_time.h>
#include <os/lock.h>
#include <pthread.h>
#include <servers/bootstrap.h>
#include <signal.h>
#include <spawn.h>
#include "protocol.h"

extern char **environ;

static double gTicksToSeconds;

static double seconds(uint64_t machTime) { return machTime * gTicksToSeconds; }

typedef struct {
    double *values;
    size_t count;
    size_t capacity;
} Samples;

static void samplesAdd(Samples *s, double v) {
    if (s->count == s->capacity) {
        s->capacity = s->capacity ? s->capacity * 2 : 256;
        s->values = realloc(s->values, s->capacity * sizeof(double));
    }
    s->values[s->count++] = v;
}

static int compareDoubles(const void *a, const void *b) {
    double x = *(const double *)a, y = *(const double *)b;
    return (x > y) - (x < y);
}

static NSString *samplesSummary(Samples *s) {
    if (s->count == 0) return @"-";
    qsort(s->values, s->count, sizeof(double), compareDoubles);
    double p50 = s->values[s->count / 2];
    double p95 = s->values[MIN(s->count - 1, (size_t)(s->count * 0.95))];
    double max = s->values[s->count - 1];
    return [NSString stringWithFormat:@"p50 %.2f p95 %.2f max %.2f ms", p50 * 1e3, p95 * 1e3, max * 1e3];
}

typedef struct {
    uint32_t ticks;
    uint32_t newFrames;
    uint32_t repeats;
    uint32_t skipped;
    Samples render;
    Samples ipc;
    Samples wait;
    Samples lead;
    Samples total;
} Stats;

static void statsReset(Stats *s) {
    s->ticks = s->newFrames = s->repeats = s->skipped = 0;
    s->render.count = s->ipc.count = s->wait.count = s->lead.count = s->total.count = 0;
}

static void statsMerge(Stats *into, Stats *from) {
    into->ticks += from->ticks;
    into->newFrames += from->newFrames;
    into->repeats += from->repeats;
    into->skipped += from->skipped;
    for (size_t i = 0; i < from->render.count; i++) samplesAdd(&into->render, from->render.values[i]);
    for (size_t i = 0; i < from->ipc.count; i++) samplesAdd(&into->ipc, from->ipc.values[i]);
    for (size_t i = 0; i < from->wait.count; i++) samplesAdd(&into->wait, from->wait.values[i]);
    for (size_t i = 0; i < from->lead.count; i++) samplesAdd(&into->lead, from->lead.values[i]);
    for (size_t i = 0; i < from->total.count; i++) samplesAdd(&into->total, from->total.values[i]);
}

static void statsPrint(const char *label, Stats *s, double elapsed) {
    fprintf(stderr,
            "%s: %.1f ticks/s, %.1f new frames/s, %u repeats, %u skipped\n"
            "  render (producer start -> gpu done)        %s\n"
            "  ipc    (gpu done -> message received)      %s\n"
            "  wait   (received -> consumer tick)         %s\n"
            "  slack  (consumer tick -> commit deadline)  %s\n"
            "  total  (producer start -> commit deadline) %s\n",
            label, s->ticks / elapsed, s->newFrames / elapsed, s->repeats, s->skipped,
            samplesSummary(&s->render).UTF8String, samplesSummary(&s->ipc).UTF8String,
            samplesSummary(&s->wait).UTF8String, samplesSummary(&s->lead).UTF8String,
            samplesSummary(&s->total).UTF8String);
}

@interface SpikeView : NSOpenGLView
@end

@implementation SpikeView {
    mach_port_t _port;
    pid_t _producer;
    IOSurfaceRef _surfaces[SPIKE_MAX_SURFACES];
    GLuint _textures[SPIKE_MAX_SURFACES];
    uint32_t _count, _width, _height;
    GLuint _program, _vao, _sizeUniform;
    mach_port_t _ticks;
    BOOL _paced;
    os_unfair_lock _lock;
    spike_frame _pending;
    uint64_t _pendingArrived;
    BOOL _hasPending;
    int32_t _current;
    uint64_t _lastSeq;
    Stats _interval, _total;
    double _intervalStart, _runStart, _runSeconds;
}

- (instancetype)initWithFrame:(NSRect)frame port:(mach_port_t)port runSeconds:(double)runSeconds paced:(BOOL)paced {
    NSOpenGLPixelFormatAttribute attrs[] = {
        NSOpenGLPFAOpenGLProfile, NSOpenGLProfileVersion3_2Core,
        NSOpenGLPFADoubleBuffer, NSOpenGLPFAAccelerated,
        NSOpenGLPFAColorSize, 24, 0,
    };
    self = [super initWithFrame:frame pixelFormat:[[NSOpenGLPixelFormat alloc] initWithAttributes:attrs]];
    _port = port;
    _current = -1;
    _runSeconds = runSeconds;
    _paced = paced;
    _lock = OS_UNFAIR_LOCK_INIT;
    self.wantsBestResolutionOpenGLSurface = YES;
    return self;
}

- (void)prepareOpenGL {
    [super prepareOpenGL];
    GLint interval = 0;
    [self.openGLContext setValues:&interval forParameter:NSOpenGLContextParameterSwapInterval];
    const char *vs =
        "#version 150\n"
        "in vec2 p; out vec2 uv; uniform vec2 size;\n"
        "void main() { uv = vec2(p.x * 0.5 + 0.5, 0.5 - p.y * 0.5) * size; gl_Position = vec4(p, 0, 1); }\n";
    const char *fs =
        "#version 150\n"
        "in vec2 uv; out vec4 color; uniform sampler2DRect tex;\n"
        "void main() { color = texture(tex, uv); }\n";
    GLuint v = glCreateShader(GL_VERTEX_SHADER), f = glCreateShader(GL_FRAGMENT_SHADER);
    glShaderSource(v, 1, &vs, NULL);
    glShaderSource(f, 1, &fs, NULL);
    glCompileShader(v);
    glCompileShader(f);
    _program = glCreateProgram();
    glAttachShader(_program, v);
    glAttachShader(_program, f);
    glBindAttribLocation(_program, 0, "p");
    glLinkProgram(_program);
    GLint linked = 0;
    glGetProgramiv(_program, GL_LINK_STATUS, &linked);
    if (!linked) {
        fprintf(stderr, "consumer: shader link failed\n");
        exit(1);
    }
    _sizeUniform = glGetUniformLocation(_program, "size");
    static const float quad[] = {-1, -1, 1, -1, -1, 1, 1, 1};
    GLuint vbo;
    glGenVertexArrays(1, &_vao);
    glBindVertexArray(_vao);
    glGenBuffers(1, &vbo);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof quad, quad, GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, NULL);
}

- (void)startReceiving {
    [NSThread detachNewThreadWithBlock:^{
        pthread_set_qos_class_self_np(QOS_CLASS_USER_INTERACTIVE, 0);
        for (;;) {
            spike_inbox in = {0};
            kern_return_t kr = mach_msg(&in.header, MACH_RCV_MSG, 0, sizeof in, self->_port, MACH_MSG_TIMEOUT_NONE, MACH_PORT_NULL);
            uint64_t arrived = mach_absolute_time();
            if (kr != KERN_SUCCESS) {
                fprintf(stderr, "consumer: receive failed: %s\n", mach_error_string(kr));
                continue;
            }
            if (in.header.msgh_id == SPIKE_MSG_HELLO) {
                spike_hello hello = in.hello.msg;
                dispatch_sync(dispatch_get_main_queue(), ^{
                    self->_ticks = in.header.msgh_remote_port;
                    [self adoptHello:&hello];
                });
            } else if (in.header.msgh_id == SPIKE_MSG_FRAME) {
                spike_frame f = in.frame.msg;
                os_unfair_lock_lock(&self->_lock);
                if (f.index < self->_count && (!self->_hasPending || f.seq > self->_pending.seq)) {
                    self->_pending = f;
                    self->_pendingArrived = arrived;
                    self->_hasPending = YES;
                }
                os_unfair_lock_unlock(&self->_lock);
            }
        }
    }];
}

- (void)requestFrame:(double)target {
    if (!_paced || _ticks == MACH_PORT_NULL) return;
    spike_tick t = {0};
    t.header.msgh_bits = MACH_MSGH_BITS_SET(MACH_MSG_TYPE_COPY_SEND, 0, 0, 0);
    t.header.msgh_size = sizeof t;
    t.header.msgh_remote_port = _ticks;
    t.header.msgh_id = SPIKE_MSG_TICK;
    t.target = (uint64_t)(target / gTicksToSeconds);
    t.sent = mach_absolute_time();
    mach_msg(&t.header, MACH_SEND_MSG | MACH_SEND_TIMEOUT, sizeof t, 0, MACH_PORT_NULL, 0, MACH_PORT_NULL);
}

- (void)adoptHello:(const spike_hello *)hello {
    [self.openGLContext makeCurrentContext];
    CGLContextObj cgl = self.openGLContext.CGLContextObj;
    _count = MIN(hello->count, SPIKE_MAX_SURFACES);
    _width = hello->width;
    _height = hello->height;
    glGenTextures((GLsizei)_count, _textures);
    for (uint32_t i = 0; i < SPIKE_MAX_SURFACES; i++) {
        mach_port_t p = hello->surfaces[i].name;
        if (i < _count) {
            _surfaces[i] = IOSurfaceLookupFromMachPort(p);
            glBindTexture(GL_TEXTURE_RECTANGLE, _textures[i]);
            CGLError err = CGLTexImageIOSurface2D(cgl, GL_TEXTURE_RECTANGLE, GL_RGBA, (GLsizei)_width, (GLsizei)_height,
                                                  GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, _surfaces[i], 0);
            if (err != kCGLNoError) {
                fprintf(stderr, "consumer: CGLTexImageIOSurface2D failed: %d\n", err);
                exit(1);
            }
            glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        }
        if (p != MACH_PORT_NULL) mach_port_deallocate(mach_task_self(), p);
    }
    fprintf(stderr, "consumer: adopted %u IOSurfaces of %ux%u\n", _count, _width, _height);
}

- (void)tick:(CADisplayLink *)link {
    uint64_t now = mach_absolute_time();
    if (_runStart == 0) _runStart = _intervalStart = seconds(now);
    os_unfair_lock_lock(&_lock);
    BOOL fresh = _hasPending;
    spike_frame f = _pending;
    uint64_t arrived = _pendingArrived;
    _hasPending = NO;
    os_unfair_lock_unlock(&_lock);
    [self requestFrame:seconds(now) + link.duration];
    _interval.ticks++;
    samplesAdd(&_interval.lead, link.targetTimestamp - seconds(now));
    if (fresh) {
        IOSurfaceIncrementUseCount(_surfaces[f.index]);
        if (_current >= 0) IOSurfaceDecrementUseCount(_surfaces[_current]);
        _current = (int32_t)f.index;
        if (_lastSeq && f.seq > _lastSeq + 1) _interval.skipped += (uint32_t)(f.seq - _lastSeq - 1);
        _lastSeq = f.seq;
        _interval.newFrames++;
        if (getenv("SPIKE_TRACE"))
            fprintf(stderr, "abs %.3f consumer tick: shows seq %llu (started %.3f) link.timestamp %.3f target %.3f\n",
                    seconds(now) * 1e3, f.seq, seconds(f.started) * 1e3, link.timestamp * 1e3, link.targetTimestamp * 1e3);
        samplesAdd(&_interval.render, seconds(f.ready - f.started));
        samplesAdd(&_interval.ipc, seconds(arrived - f.ready));
        samplesAdd(&_interval.wait, seconds(now) - seconds(arrived));
        samplesAdd(&_interval.total, link.targetTimestamp - seconds(f.started));
    } else if (_current >= 0) {
        _interval.repeats++;
    }
    [self draw];

    double t = seconds(mach_absolute_time());
    if (t - _intervalStart >= 2.0) {
        statsPrint("consumer", &_interval, t - _intervalStart);
        statsMerge(&_total, &_interval);
        statsReset(&_interval);
        _intervalStart = t;
    }
    if (_runSeconds > 0 && t - _runStart >= _runSeconds) [self finish];
}

- (void)draw {
    [self.openGLContext makeCurrentContext];
    NSSize px = [self convertSizeToBacking:self.bounds.size];
    glViewport(0, 0, (GLsizei)px.width, (GLsizei)px.height);
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT);
    if (_current >= 0) {
        glUseProgram(_program);
        glUniform2f(_sizeUniform, (float)_width, (float)_height);
        glBindTexture(GL_TEXTURE_RECTANGLE, _textures[_current]);
        glBindVertexArray(_vao);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }
    [self.openGLContext flushBuffer];
}

- (void)setProducer:(pid_t)pid {
    _producer = pid;
}

- (void)finish {
    statsMerge(&_total, &_interval);
    double elapsed = seconds(mach_absolute_time()) - _runStart;
    statsPrint("summary", &_total, elapsed);
    struct rusage ru;
    getrusage(RUSAGE_SELF, &ru);
    fprintf(stderr, "consumer: cpu %.2fs over %.1fs\n",
            ru.ru_utime.tv_sec + ru.ru_stime.tv_sec + (ru.ru_utime.tv_usec + ru.ru_stime.tv_usec) / 1e6, elapsed);
    if (_producer > 0) kill(_producer, SIGTERM);
    exit(0);
}

- (BOOL)acceptsFirstResponder {
    return YES;
}

- (void)keyDown:(NSEvent *)event {
    if ([event.charactersIgnoringModifiers isEqualToString:@"q"]) [self finish];
}

@end

static NSString *option(NSArray<NSString *> *args, NSString *name, NSString *fallback) {
    NSUInteger i = [args indexOfObject:name];
    return i != NSNotFound && i + 1 < args.count ? args[i + 1] : fallback;
}

int main(int argc, const char *argv[]) {
    @autoreleasepool {
        mach_timebase_info_data_t tb;
        mach_timebase_info(&tb);
        gTicksToSeconds = (double)tb.numer / tb.denom / 1e9;

        NSArray<NSString *> *args = NSProcessInfo.processInfo.arguments;
        NSArray<NSString *> *size = [option(args, @"--size", @"1920x1080") componentsSeparatedByString:@"x"];
        NSString *surfaces = option(args, @"--surfaces", @"4");
        double runSeconds = option(args, @"--seconds", @"0").doubleValue;
        NSString *mode = option(args, @"--mode", @"paced");
        NSString *margin = option(args, @"--margin", @"4");

        [NSApplication sharedApplication];
        NSApp.activationPolicy = NSApplicationActivationPolicyRegular;
        NSString *rate = option(args, @"--rate", [@(NSScreen.mainScreen.maximumFramesPerSecond) stringValue]);

        char service[128];
        snprintf(service, sizeof service, "dev.halone.spike.shared-texture.%d", getpid());
        mach_port_t port = MACH_PORT_NULL;
        kern_return_t kr = bootstrap_check_in(bootstrap_port, service, &port);
        if (kr != KERN_SUCCESS) {
            fprintf(stderr, "consumer: bootstrap_check_in failed: %s\n", bootstrap_strerror(kr));
            return 1;
        }

        NSRect frame = NSMakeRect(0, 0, 1280, 720);
        NSWindow *window = [[NSWindow alloc] initWithContentRect:frame
                                                       styleMask:NSWindowStyleMaskTitled | NSWindowStyleMaskClosable | NSWindowStyleMaskResizable
                                                         backing:NSBackingStoreBuffered
                                                           defer:NO];
        window.title = @"Halone shared-texture spike";
        SpikeView *view = [[SpikeView alloc] initWithFrame:frame port:port runSeconds:runSeconds paced:[mode isEqualToString:@"paced"]];
        window.contentView = view;
        [window center];
        [window makeKeyAndOrderFront:nil];
        [window makeFirstResponder:view];
        [NSApp activate];

        NSString *producerPath = [[NSString stringWithUTF8String:argv[0]].stringByDeletingLastPathComponent
            stringByAppendingPathComponent:@"producer"];
        const char *pargv[] = {
            producerPath.UTF8String, service, size[0].UTF8String, size[1].UTF8String,
            surfaces.UTF8String, rate.UTF8String, mode.UTF8String, margin.UTF8String, NULL,
        };
        pid_t pid = 0;
        int err = posix_spawn(&pid, pargv[0], NULL, NULL, (char *const *)pargv, environ);
        if (err) {
            fprintf(stderr, "consumer: spawning %s failed: %s\n", pargv[0], strerror(err));
            return 1;
        }
        [view setProducer:pid];
        fprintf(stderr, "consumer: producer %d, %sx%s, %s surfaces, %s at %s Hz, margin %s ms\n", pid, size[0].UTF8String,
                size[1].UTF8String, surfaces.UTF8String, mode.UTF8String, rate.UTF8String, margin.UTF8String);
        [view startReceiving];

        dispatch_source_t exited = dispatch_source_create(DISPATCH_SOURCE_TYPE_PROC, (uintptr_t)pid, DISPATCH_PROC_EXIT,
                                                          dispatch_get_main_queue());
        dispatch_source_set_event_handler(exited, ^{
            int status = 0;
            waitpid(pid, &status, WNOHANG);
            fprintf(stderr, "consumer: producer exited (status %d); last frame stays on screen\n", status);
            dispatch_source_cancel(exited);
        });
        dispatch_resume(exited);

        CADisplayLink *link = [view displayLinkWithTarget:view selector:@selector(tick:)];
        [link addToRunLoop:NSRunLoop.mainRunLoop forMode:NSRunLoopCommonModes];

        [NSApp run];
    }
    return 0;
}
