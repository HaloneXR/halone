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

#define MAX_PANELS 8

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
    return [NSString stringWithFormat:@"p50 %6.2f p95 %6.2f max %6.2f ms", p50 * 1e3, p95 * 1e3, max * 1e3];
}

typedef struct {
    uint32_t ticks;
    uint32_t newFrames;
    uint32_t idle;
    uint32_t skipped;
    Samples paint;
    Samples blit;
    Samples ipc;
    Samples wait;
    Samples slack;
    Samples total;
} Stats;

static Samples *statsSamples(Stats *s, int i) {
    Samples *all[] = {&s->paint, &s->blit, &s->ipc, &s->wait, &s->slack, &s->total};
    return all[i];
}

static void statsReset(Stats *s) {
    s->ticks = s->newFrames = s->idle = s->skipped = 0;
    for (int i = 0; i < 6; i++) statsSamples(s, i)->count = 0;
}

static void statsMerge(Stats *into, Stats *from) {
    into->ticks += from->ticks;
    into->newFrames += from->newFrames;
    into->idle += from->idle;
    into->skipped += from->skipped;
    for (int i = 0; i < 6; i++) {
        Samples *a = statsSamples(into, i), *b = statsSamples(from, i);
        for (size_t k = 0; k < b->count; k++) samplesAdd(a, b->values[k]);
    }
}

static void statsPrint(const char *label, uint32_t panel, Stats *s, double elapsed) {
    fprintf(stderr,
            "%s panel %u: %.1f new frames/s, %u idle ticks, %u skipped\n"
            "  paint (begin frame -> CEF paint)        %s\n"
            "  blit  (CEF paint -> copy done)          %s\n"
            "  ipc   (copy done -> message received)   %s\n"
            "  wait  (received -> consumer tick)       %s\n"
            "  slack (consumer tick -> commit deadline) %s\n"
            "  total (begin frame -> commit deadline)  %s\n",
            label, panel, s->newFrames / elapsed, s->idle, s->skipped, samplesSummary(&s->paint).UTF8String,
            samplesSummary(&s->blit).UTF8String, samplesSummary(&s->ipc).UTF8String,
            samplesSummary(&s->wait).UTF8String, samplesSummary(&s->slack).UTF8String, samplesSummary(&s->total).UTF8String);
}

typedef struct {
    uint32_t width, height, count;
    IOSurfaceRef surfaces[SPIKE_MAX_SURFACES];
    GLuint textures[SPIKE_MAX_SURFACES];
    int32_t current;
    spike_frame pending;
    uint64_t pendingArrived;
    BOOL hasPending;
    uint64_t lastSeq;
    NSRect rect;
    Stats interval, total;
} PanelState;

@interface SpikeView : NSOpenGLView
@property(nonatomic) pid_t producer;
@property(nonatomic) BOOL inputTest;
@end

@implementation SpikeView {
    mach_port_t _port;
    mach_port_t _producerInbox;
    os_unfair_lock _lock;
    PanelState _panels[MAX_PANELS];
    uint32_t _panelCount;
    GLuint _program, _vao, _sizeUniform;
    double _intervalStart, _runStart, _runSeconds;
    int32_t _hovered;
    BOOL _finished;
    double _warmup;
    uint64_t _firstFrame;
    NSDictionary<NSString *, NSNumber *> *_cpuStart;
    double _ownCpuStart;
}

- (instancetype)initWithFrame:(NSRect)frame port:(mach_port_t)port panels:(uint32_t)count runSeconds:(double)runSeconds warmup:(double)warmup {
    NSOpenGLPixelFormatAttribute attrs[] = {
        NSOpenGLPFAOpenGLProfile, NSOpenGLProfileVersion3_2Core,
        NSOpenGLPFADoubleBuffer, NSOpenGLPFAAccelerated,
        NSOpenGLPFAColorSize, 24, 0,
    };
    self = [super initWithFrame:frame pixelFormat:[[NSOpenGLPixelFormat alloc] initWithAttributes:attrs]];
    _port = port;
    _panelCount = count;
    _runSeconds = runSeconds;
    _warmup = warmup;
    _lock = OS_UNFAIR_LOCK_INIT;
    _hovered = -1;
    for (uint32_t i = 0; i < count; i++) _panels[i].current = -1;
    self.wantsBestResolutionOpenGLSurface = YES;
    [self addTrackingArea:[[NSTrackingArea alloc] initWithRect:NSZeroRect
                                                       options:NSTrackingMouseMoved | NSTrackingActiveAlways | NSTrackingInVisibleRect
                                                         owner:self
                                                      userInfo:nil]];
    return self;
}

- (void)setPanel:(uint32_t)i width:(uint32_t)w height:(uint32_t)h rect:(NSRect)rect {
    _panels[i].width = w;
    _panels[i].height = h;
    _panels[i].rect = rect;
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
            spike_consumer_inbox in = {0};
            kern_return_t kr = mach_msg(&in.header, MACH_RCV_MSG, 0, sizeof in, self->_port, MACH_MSG_TIMEOUT_NONE, MACH_PORT_NULL);
            uint64_t arrived = mach_absolute_time();
            if (kr != KERN_SUCCESS) continue;
            if (in.header.msgh_id == SPIKE_MSG_HELLO) {
                spike_hello hello = in.hello.msg;
                mach_port_t reply = in.header.msgh_remote_port;
                dispatch_sync(dispatch_get_main_queue(), ^{
                    if (self->_producerInbox == MACH_PORT_NULL) self->_producerInbox = reply;
                    else mach_port_deallocate(mach_task_self(), reply);
                    [self adoptHello:&hello];
                });
            } else if (in.header.msgh_id == SPIKE_MSG_FRAME) {
                spike_frame f = in.frame.msg;
                if (f.panel >= self->_panelCount) continue;
                os_unfair_lock_lock(&self->_lock);
                PanelState *p = &self->_panels[f.panel];
                if (f.index < p->count && (!p->hasPending || f.seq > p->pending.seq)) {
                    p->pending = f;
                    p->pendingArrived = arrived;
                    p->hasPending = YES;
                }
                os_unfair_lock_unlock(&self->_lock);
            }
        }
    }];
}

- (void)adoptHello:(const spike_hello *)hello {
    if (hello->panel >= _panelCount) return;
    PanelState *p = &_panels[hello->panel];
    [self.openGLContext makeCurrentContext];
    CGLContextObj cgl = self.openGLContext.CGLContextObj;
    p->count = MIN(hello->count, SPIKE_MAX_SURFACES);
    p->width = hello->width;
    p->height = hello->height;
    glGenTextures((GLsizei)p->count, p->textures);
    for (uint32_t i = 0; i < SPIKE_MAX_SURFACES; i++) {
        mach_port_t port = hello->surfaces[i].name;
        if (i < p->count) {
            p->surfaces[i] = IOSurfaceLookupFromMachPort(port);
            glBindTexture(GL_TEXTURE_RECTANGLE, p->textures[i]);
            CGLTexImageIOSurface2D(cgl, GL_TEXTURE_RECTANGLE, GL_RGBA, (GLsizei)p->width, (GLsizei)p->height, GL_BGRA,
                                   GL_UNSIGNED_INT_8_8_8_8_REV, p->surfaces[i], 0);
            glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        }
        if (port != MACH_PORT_NULL) mach_port_deallocate(mach_task_self(), port);
    }
    fprintf(stderr, "consumer: panel %u adopted %u IOSurfaces of %ux%u\n", hello->panel, p->count, p->width, p->height);
}

- (void)send:(mach_msg_header_t *)header size:(mach_msg_size_t)size {
    if (_producerInbox == MACH_PORT_NULL) return;
    header->msgh_bits = MACH_MSGH_BITS_SET(MACH_MSG_TYPE_COPY_SEND, 0, 0, 0);
    header->msgh_size = size;
    header->msgh_remote_port = _producerInbox;
    mach_msg(header, MACH_SEND_MSG | MACH_SEND_TIMEOUT, size, 0, MACH_PORT_NULL, 0, MACH_PORT_NULL);
}

- (void)tick:(CADisplayLink *)link {
    uint64_t now = mach_absolute_time();
    spike_frame fresh[MAX_PANELS];
    uint64_t arrived[MAX_PANELS];
    BOOL has[MAX_PANELS];
    os_unfair_lock_lock(&_lock);
    for (uint32_t i = 0; i < _panelCount; i++) {
        has[i] = _panels[i].hasPending;
        fresh[i] = _panels[i].pending;
        arrived[i] = _panels[i].pendingArrived;
        _panels[i].hasPending = NO;
    }
    os_unfair_lock_unlock(&_lock);

    spike_tick t = {0};
    t.header.msgh_id = SPIKE_MSG_TICK;
    t.target = (uint64_t)((seconds(now) + link.duration) / gTicksToSeconds);
    t.sent = mach_absolute_time();
    [self send:&t.header size:sizeof t];

    for (uint32_t i = 0; i < _panelCount; i++) {
        PanelState *p = &_panels[i];
        p->interval.ticks++;
        if (!has[i]) {
            if (p->current >= 0) p->interval.idle++;
            continue;
        }
        spike_frame f = fresh[i];
        IOSurfaceIncrementUseCount(p->surfaces[f.index]);
        if (p->current >= 0) IOSurfaceDecrementUseCount(p->surfaces[p->current]);
        p->current = (int32_t)f.index;
        if (p->lastSeq && f.seq > p->lastSeq + 1) p->interval.skipped += (uint32_t)(f.seq - p->lastSeq - 1);
        p->lastSeq = f.seq;
        p->interval.newFrames++;
        if (f.begun && f.painted >= f.begun) {
            samplesAdd(&p->interval.paint, seconds(f.painted - f.begun));
            samplesAdd(&p->interval.total, link.targetTimestamp - seconds(f.begun));
        }
        samplesAdd(&p->interval.blit, seconds(f.ready - f.painted));
        samplesAdd(&p->interval.ipc, seconds(arrived[i]) - seconds(f.ready));
        samplesAdd(&p->interval.wait, seconds(now) - seconds(arrived[i]));
        samplesAdd(&p->interval.slack, link.targetTimestamp - seconds(now));
    }
    [self draw];

    double t2 = seconds(mach_absolute_time());
    for (uint32_t i = 0; i < _panelCount && !_firstFrame; i++)
        if (_panels[i].current >= 0) _firstFrame = now;
    if (!_firstFrame || t2 - seconds(_firstFrame) < _warmup) {
        for (uint32_t i = 0; i < _panelCount; i++) statsReset(&_panels[i].interval);
        return;
    }
    if (_runStart == 0) {
        _runStart = _intervalStart = t2;
        for (uint32_t i = 0; i < _panelCount; i++) statsReset(&_panels[i].interval);
        _cpuStart = [self cpuByKind];
        _ownCpuStart = [self ownCpu];
        fprintf(stderr, "consumer: warm-up over, measuring\n");
        if (_inputTest) [self runInputTest];
        return;
    }
    if (t2 - _intervalStart >= 2.0) {
        for (uint32_t i = 0; i < _panelCount; i++) {
            statsPrint("consumer", i, &_panels[i].interval, t2 - _intervalStart);
            statsMerge(&_panels[i].total, &_panels[i].interval);
            statsReset(&_panels[i].interval);
        }
        _intervalStart = t2;
    }
    if (_runSeconds > 0 && t2 - _runStart >= _runSeconds) [self finish];
}

- (void)draw {
    [self.openGLContext makeCurrentContext];
    NSSize backing = [self convertSizeToBacking:self.bounds.size];
    glViewport(0, 0, (GLsizei)backing.width, (GLsizei)backing.height);
    glClearColor(0.08, 0.08, 0.08, 1);
    glClear(GL_COLOR_BUFFER_BIT);
    glUseProgram(_program);
    glBindVertexArray(_vao);
    for (uint32_t i = 0; i < _panelCount; i++) {
        PanelState *p = &_panels[i];
        if (p->current < 0) continue;
        NSRect r = [self convertRectToBacking:p->rect];
        glViewport((GLint)r.origin.x, (GLint)r.origin.y, (GLsizei)r.size.width, (GLsizei)r.size.height);
        glUniform2f(_sizeUniform, (float)p->width, (float)p->height);
        glBindTexture(GL_TEXTURE_RECTANGLE, p->textures[p->current]);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }
    [self.openGLContext flushBuffer];
}

- (int32_t)panelAt:(NSPoint)point x:(float *)x y:(float *)y {
    for (uint32_t i = 0; i < _panelCount; i++) {
        NSRect r = _panels[i].rect;
        if (NSPointInRect(point, r)) {
            *x = (float)((point.x - r.origin.x) / r.size.width * _panels[i].width);
            *y = (float)((r.origin.y + r.size.height - point.y) / r.size.height * _panels[i].height);
            return (int32_t)i;
        }
    }
    return -1;
}

- (void)forwardMouse:(NSEvent *)event kind:(uint32_t)kind {
    NSPoint point = [self convertPoint:event.locationInWindow fromView:nil];
    float x = 0, y = 0;
    int32_t panel = [self panelAt:point x:&x y:&y];
    if (_hovered >= 0 && panel != _hovered) {
        spike_input leave = {0};
        leave.header.msgh_id = SPIKE_MSG_INPUT;
        leave.panel = (uint32_t)_hovered;
        leave.kind = SPIKE_INPUT_LEAVE;
        [self send:&leave.header size:sizeof leave];
    }
    _hovered = panel;
    if (panel < 0) return;
    spike_input in = {0};
    in.header.msgh_id = SPIKE_MSG_INPUT;
    in.panel = (uint32_t)panel;
    in.kind = kind;
    in.x = x;
    in.y = y;
    in.modifiers = (uint32_t)event.modifierFlags;
    if (kind == SPIKE_INPUT_MOVE) {
        in.button = (uint32_t)NSEvent.pressedMouseButtons;
    } else if (kind == SPIKE_INPUT_WHEEL) {
        in.dx = (float)event.scrollingDeltaX * (event.hasPreciseScrollingDeltas ? 1 : 40);
        in.dy = (float)event.scrollingDeltaY * (event.hasPreciseScrollingDeltas ? 1 : 40);
    } else {
        in.button = (uint32_t)event.buttonNumber;
        in.clicks = (uint32_t)event.clickCount;
    }
    [self send:&in.header size:sizeof in];
}

- (void)forwardKey:(NSEvent *)event kind:(uint32_t)kind {
    spike_input in = {0};
    in.header.msgh_id = SPIKE_MSG_INPUT;
    in.panel = _hovered >= 0 ? (uint32_t)_hovered : 0;
    in.kind = kind;
    in.keyCode = event.keyCode;
    in.modifiers = (uint32_t)event.modifierFlags;
    in.character = event.characters.length ? [event.characters characterAtIndex:0] : 0;
    in.unmodified = event.charactersIgnoringModifiers.length ? [event.charactersIgnoringModifiers characterAtIndex:0] : 0;
    [self send:&in.header size:sizeof in];
}

- (void)input:(uint32_t)kind x:(float)x y:(float)y after:(double)delay configure:(void (^)(spike_input *))configure {
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(delay * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
        spike_input in = {0};
        in.header.msgh_id = SPIKE_MSG_INPUT;
        in.panel = 0;
        in.kind = kind;
        in.x = x;
        in.y = y;
        if (configure) configure(&in);
        [self send:&in.header size:sizeof in];
    });
}

- (void)runInputTest {
    fprintf(stderr, "consumer: running the scripted input test on panel 0\n");
    for (int i = 0; i <= 40; i++)
        [self input:SPIKE_INPUT_MOVE x:100 + (265 - 100) * i / 40.0f y:100 + (505 - 100) * i / 40.0f after:i * 0.016 configure:nil];
    void (^click)(spike_input *) = ^(spike_input *in) { in->clicks = 1; };
    [self input:SPIKE_INPUT_DOWN x:265 y:505 after:0.8 configure:click];
    [self input:SPIKE_INPUT_UP x:265 y:505 after:0.85 configure:click];
    [self input:SPIKE_INPUT_DOWN x:117 y:505 after:1.2 configure:click];
    [self input:SPIKE_INPUT_UP x:117 y:505 after:1.25 configure:click];
    const char *word = "hello";
    const uint32_t codes[] = {4, 14, 37, 37, 31};
    for (int i = 0; i < 5; i++) {
        uint16_t c = (uint16_t)word[i];
        uint32_t code = codes[i];
        [self input:SPIKE_INPUT_KEY_DOWN x:0 y:0 after:1.5 + i * 0.1 configure:^(spike_input *in) {
            in->keyCode = code;
            in->character = in->unmodified = c;
        }];
        [self input:SPIKE_INPUT_KEY_UP x:0 y:0 after:1.55 + i * 0.1 configure:^(spike_input *in) {
            in->keyCode = code;
            in->character = in->unmodified = c;
        }];
    }
    for (int i = 0; i < 3; i++)
        [self input:SPIKE_INPUT_WHEEL x:324 y:601 after:2.3 + i * 0.1 configure:^(spike_input *in) { in->dy = -120; }];
    [self input:SPIKE_INPUT_DOWN x:640 y:300 after:2.8 configure:^(spike_input *in) { in->button = 1; in->clicks = 1; }];
    [self input:SPIKE_INPUT_UP x:640 y:300 after:2.85 configure:^(spike_input *in) { in->button = 1; in->clicks = 1; }];
}

- (BOOL)acceptsFirstResponder {
    return YES;
}

- (void)mouseMoved:(NSEvent *)e { [self forwardMouse:e kind:SPIKE_INPUT_MOVE]; }
- (void)mouseDragged:(NSEvent *)e { [self forwardMouse:e kind:SPIKE_INPUT_MOVE]; }
- (void)rightMouseDragged:(NSEvent *)e { [self forwardMouse:e kind:SPIKE_INPUT_MOVE]; }
- (void)mouseDown:(NSEvent *)e { [self forwardMouse:e kind:SPIKE_INPUT_DOWN]; }
- (void)mouseUp:(NSEvent *)e { [self forwardMouse:e kind:SPIKE_INPUT_UP]; }
- (void)rightMouseDown:(NSEvent *)e { [self forwardMouse:e kind:SPIKE_INPUT_DOWN]; }
- (void)rightMouseUp:(NSEvent *)e { [self forwardMouse:e kind:SPIKE_INPUT_UP]; }
- (void)scrollWheel:(NSEvent *)e { [self forwardMouse:e kind:SPIKE_INPUT_WHEEL]; }
- (void)keyUp:(NSEvent *)e { [self forwardKey:e kind:SPIKE_INPUT_KEY_UP]; }

- (void)keyDown:(NSEvent *)e {
    if ((e.modifierFlags & NSEventModifierFlagCommand) && [e.charactersIgnoringModifiers isEqualToString:@"q"]) {
        [self finish];
        return;
    }
    [self forwardKey:e kind:SPIKE_INPUT_KEY_DOWN];
}

- (NSDictionary<NSString *, NSNumber *> *)cpuByKind {
    NSTask *task = [NSTask new];
    task.executableURL = [NSURL fileURLWithPath:@"/bin/ps"];
    task.arguments = @[ @"-A", @"-o", @"time=,command=" ];
    NSPipe *pipe = [NSPipe pipe];
    task.standardOutput = pipe;
    [task launchAndReturnError:nil];
    NSData *data = [pipe.fileHandleForReading readDataToEndOfFile];
    [task waitUntilExit];
    NSMutableDictionary<NSString *, NSNumber *> *byKind = [NSMutableDictionary dictionary];
    for (NSString *line in [[[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding] componentsSeparatedByString:@"\n"]) {
        NSString *trimmed = [line stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceCharacterSet];
        NSRange space = [trimmed rangeOfString:@" "];
        if (space.location == NSNotFound) continue;
        NSString *command = [trimmed substringFromIndex:space.location + 1];
        if (![command containsString:@"Halone CEF Spike"]) continue;
        double cpu = 0;
        for (NSString *part in [[trimmed substringToIndex:space.location] componentsSeparatedByString:@":"]) cpu = cpu * 60 + part.doubleValue;
        NSString *kind = @"browser";
        for (NSString *k in @[ @"(GPU)", @"(Renderer)", @"(Plugin)", @"(Alerts)" ])
            if ([command containsString:k]) kind = [k substringWithRange:NSMakeRange(1, k.length - 2)];
        if ([kind isEqualToString:@"browser"] && [command containsString:@"Helper"]) kind = @"utility";
        byKind[kind] = @(byKind[kind].doubleValue + cpu);
    }
    return byKind;
}

- (double)ownCpu {
    struct rusage ru;
    getrusage(RUSAGE_SELF, &ru);
    return ru.ru_utime.tv_sec + ru.ru_stime.tv_sec + (ru.ru_utime.tv_usec + ru.ru_stime.tv_usec) / 1e6;
}

- (void)printProcessCpu:(double)elapsed {
    NSDictionary<NSString *, NSNumber *> *end = [self cpuByKind];
    double sum = 0;
    for (NSString *kind in [end.allKeys sortedArrayUsingSelector:@selector(compare:)]) {
        double used = end[kind].doubleValue - _cpuStart[kind].doubleValue;
        fprintf(stderr, "cpu: %-9s %5.1f%% of a core\n", kind.UTF8String, used / elapsed * 100);
        sum += used;
    }
    fprintf(stderr, "cpu: %-9s %5.1f%% of a core\ncpu: %-9s %5.1f%% of a core\n", "CEF total", sum / elapsed * 100, "consumer",
            ([self ownCpu] - _ownCpuStart) / elapsed * 100);
}

- (void)finish {
    if (_finished) return;
    _finished = YES;
    double elapsed = seconds(mach_absolute_time()) - _runStart;
    for (uint32_t i = 0; i < _panelCount; i++) {
        statsMerge(&_panels[i].total, &_panels[i].interval);
        statsPrint("summary", i, &_panels[i].total, elapsed);
    }
    [self printProcessCpu:elapsed];
    if (_producer > 0) kill(_producer, SIGTERM);
    exit(0);
}

@end

int main(int argc, const char *argv[]) {
    @autoreleasepool {
        mach_timebase_info_data_t tb;
        mach_timebase_info(&tb);
        gTicksToSeconds = (double)tb.numer / tb.denom / 1e9;

        NSArray<NSString *> *args = NSProcessInfo.processInfo.arguments;
        NSMutableArray<NSString *> *panels = [NSMutableArray array];
        NSString *margin = @"4", *surfaces = @"4";
        double runSeconds = 0, panelHeight = 360, warmup = 4;
        BOOL inputTest = NO;
        NSString *producerPath = [[NSString stringWithUTF8String:argv[0]].stringByDeletingLastPathComponent
            stringByAppendingPathComponent:@"Halone CEF Spike.app/Contents/MacOS/Halone CEF Spike"];
        for (NSUInteger i = 1; i + 1 < args.count; i += 2) {
            NSString *name = args[i], *value = args[i + 1];
            if ([name isEqualToString:@"--panel"]) [panels addObject:value];
            else if ([name isEqualToString:@"--margin"]) margin = value;
            else if ([name isEqualToString:@"--surfaces"]) surfaces = value;
            else if ([name isEqualToString:@"--seconds"]) runSeconds = value.doubleValue;
            else if ([name isEqualToString:@"--warmup"]) warmup = value.doubleValue;
            else if ([name isEqualToString:@"--input-test"]) inputTest = value.boolValue;
            else if ([name isEqualToString:@"--panel-height"]) panelHeight = value.doubleValue;
            else if ([name isEqualToString:@"--producer"]) producerPath = value;
        }
        if (panels.count == 0 || panels.count > MAX_PANELS) {
            fprintf(stderr, "usage: consumer --panel WxH@url [--panel ...] [--margin ms] [--surfaces n] [--seconds s] [--panel-height pt]\n");
            return 2;
        }

        [NSApplication sharedApplication];
        NSApp.activationPolicy = NSApplicationActivationPolicyRegular;

        char service[128];
        snprintf(service, sizeof service, "dev.halone.spike.cef.%d", getpid());
        mach_port_t port = MACH_PORT_NULL;
        if (bootstrap_check_in(bootstrap_port, service, &port) != KERN_SUCCESS) {
            fprintf(stderr, "consumer: bootstrap_check_in failed\n");
            return 1;
        }

        double gap = 8, x = gap, maxWidth = NSScreen.mainScreen.visibleFrame.size.width - 40;
        NSRect rects[MAX_PANELS];
        uint32_t sizes[MAX_PANELS][2];
        double totalWidth = gap;
        for (NSUInteger i = 0; i < panels.count; i++) {
            NSString *size = [panels[i] componentsSeparatedByString:@"@"][0];
            NSArray *wh = [size componentsSeparatedByString:@"x"];
            sizes[i][0] = (uint32_t)[wh[0] intValue];
            sizes[i][1] = (uint32_t)[wh[1] intValue];
            totalWidth += panelHeight * sizes[i][0] / sizes[i][1] + gap;
        }
        if (totalWidth > maxWidth) panelHeight *= maxWidth / totalWidth;
        for (NSUInteger i = 0; i < panels.count; i++) {
            double w = panelHeight * sizes[i][0] / sizes[i][1];
            rects[i] = NSMakeRect(x, gap, w, panelHeight);
            x += w + gap;
        }
        NSRect frame = NSMakeRect(0, 0, x, panelHeight + 2 * gap);
        NSWindow *window = [[NSWindow alloc] initWithContentRect:frame
                                                       styleMask:NSWindowStyleMaskTitled | NSWindowStyleMaskClosable
                                                         backing:NSBackingStoreBuffered
                                                           defer:NO];
        window.title = @"Halone CEF spike";
        window.acceptsMouseMovedEvents = YES;
        SpikeView *view = [[SpikeView alloc] initWithFrame:frame port:port panels:(uint32_t)panels.count runSeconds:runSeconds warmup:warmup];
        for (NSUInteger i = 0; i < panels.count; i++) [view setPanel:(uint32_t)i width:sizes[i][0] height:sizes[i][1] rect:rects[i]];
        window.contentView = view;
        [window center];
        [window makeKeyAndOrderFront:nil];
        fprintf(stderr, "consumer: window %ld\n", (long)window.windowNumber);
        [window makeFirstResponder:view];
        [NSApp activate];

        NSString *serviceArg = [NSString stringWithFormat:@"--spike-service=%s", service];
        NSString *panelsArg = [@"--spike-panels=" stringByAppendingString:[panels componentsJoinedByString:@","]];
        NSString *marginArg = [@"--spike-margin=" stringByAppendingString:margin];
        NSString *surfacesArg = [@"--spike-surfaces=" stringByAppendingString:surfaces];
        const char *pargv[] = {producerPath.UTF8String, serviceArg.UTF8String, panelsArg.UTF8String, marginArg.UTF8String,
                               surfacesArg.UTF8String, NULL};
        pid_t pid = 0;
        int err = posix_spawn(&pid, pargv[0], NULL, NULL, (char *const *)pargv, environ);
        if (err) {
            fprintf(stderr, "consumer: spawning %s failed: %s\n", pargv[0], strerror(err));
            return 1;
        }
        view.producer = pid;
        view.inputTest = inputTest;
        fprintf(stderr, "consumer: producer %d, %lu panels, margin %s ms\n", pid, (unsigned long)panels.count, margin.UTF8String);
        [view startReceiving];

        dispatch_source_t exited = dispatch_source_create(DISPATCH_SOURCE_TYPE_PROC, (uintptr_t)pid, DISPATCH_PROC_EXIT,
                                                          dispatch_get_main_queue());
        dispatch_source_set_event_handler(exited, ^{
            int status = 0;
            waitpid(pid, &status, WNOHANG);
            fprintf(stderr, "consumer: producer exited (status %d)\n", status);
            dispatch_source_cancel(exited);
        });
        dispatch_resume(exited);

        CADisplayLink *link = [view displayLinkWithTarget:view selector:@selector(tick:)];
        [link addToRunLoop:NSRunLoop.mainRunLoop forMode:NSRunLoopCommonModes];
        [NSApp run];
    }
    return 0;
}
