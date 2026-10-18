#import <Foundation/Foundation.h>
#import <IOSurface/IOSurface.h>
#import <Metal/Metal.h>
#include <mach/mach_time.h>
#include <servers/bootstrap.h>
#include <stdatomic.h>
#include "protocol.h"

static NSString *const kShader = @
    "#include <metal_stdlib>\n"
    "using namespace metal;\n"
    "struct V { float4 p [[position]]; };\n"
    "vertex V vs(uint id [[vertex_id]]) {\n"
    "  float2 p = float2((id << 1) & 2, id & 2);\n"
    "  return { float4(p * 2.0 - 1.0, 0, 1) };\n"
    "}\n"
    "struct U { float t; uint seq; float2 size; };\n"
    "fragment float4 fs(V in [[stage_in]], constant U& u [[buffer(0)]]) {\n"
    "  float2 px = in.p.xy;\n"
    "  float2 uv = px / u.size;\n"
    "  float3 c = 0.5 + 0.4 * cos(u.t * 0.7 + uv.xyx * 3.0 + float3(0, 2, 4));\n"
    "  float bar = fract(u.t * 0.25) * u.size.x;\n"
    "  if (abs(px.x - bar) < 24.0) c = float3(1);\n"
    "  float2 cell = floor(px / 32.0);\n"
    "  if (cell.y == 1.0 && cell.x >= 1.0 && cell.x <= 16.0) {\n"
    "    uint bit = 16u - uint(cell.x);\n"
    "    float2 in_cell = fract(px / 32.0);\n"
    "    bool edge = any(in_cell < 0.1) || any(in_cell > 0.9);\n"
    "    c = edge ? float3(0) : (((u.seq >> bit) & 1u) ? float3(1, 0.2, 0.2) : float3(0.1));\n"
    "  }\n"
    "  return float4(c, 1);\n"
    "}\n";

typedef struct {
    float t;
    uint32_t seq;
    float size[2];
} Uniforms;

static IOSurfaceRef surfaces[SPIKE_MAX_SURFACES];
static id<MTLTexture> textures[SPIKE_MAX_SURFACES];

static void fail(const char *what, kern_return_t kr) {
    fprintf(stderr, "producer: %s failed: %s (%d)\n", what, mach_error_string(kr), kr);
    exit(1);
}

int main(int argc, const char *argv[]) {
    @autoreleasepool {
        if (argc < 8) {
            fprintf(stderr, "usage: producer <service> <width> <height> <surfaces> <rate> <paced|free> <margin ms>\n");
            return 2;
        }
        const char *service = argv[1];
        uint32_t width = (uint32_t)atoi(argv[2]);
        uint32_t height = (uint32_t)atoi(argv[3]);
        uint32_t count = (uint32_t)atoi(argv[4]);
        double rate = atof(argv[5]);
        BOOL paced = strcmp(argv[6], "paced") == 0;
        double marginMs = atof(argv[7]);
        if (count < 2 || count > SPIKE_MAX_SURFACES) {
            fprintf(stderr, "producer: surfaces must be 2..%d\n", SPIKE_MAX_SURFACES);
            return 2;
        }

        mach_port_t consumer = MACH_PORT_NULL;
        kern_return_t kr = bootstrap_look_up(bootstrap_port, service, &consumer);
        if (kr != KERN_SUCCESS) fail("bootstrap_look_up", kr);

        id<MTLDevice> device = MTLCreateSystemDefaultDevice();
        id<MTLCommandQueue> queue = [device newCommandQueue];
        NSError *error = nil;
        id<MTLLibrary> library = [device newLibraryWithSource:kShader options:nil error:&error];
        if (!library) {
            fprintf(stderr, "producer: shader: %s\n", error.localizedDescription.UTF8String);
            return 1;
        }
        MTLRenderPipelineDescriptor *pd = [MTLRenderPipelineDescriptor new];
        pd.vertexFunction = [library newFunctionWithName:@"vs"];
        pd.fragmentFunction = [library newFunctionWithName:@"fs"];
        pd.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
        id<MTLRenderPipelineState> pipeline = [device newRenderPipelineStateWithDescriptor:pd error:&error];
        if (!pipeline) {
            fprintf(stderr, "producer: pipeline: %s\n", error.localizedDescription.UTF8String);
            return 1;
        }

        spike_hello hello = {0};
        for (uint32_t i = 0; i < count; i++) {
            NSDictionary *props = @{
                (id)kIOSurfaceWidth: @(width),
                (id)kIOSurfaceHeight: @(height),
                (id)kIOSurfaceBytesPerElement: @4,
                (id)kIOSurfacePixelFormat: @((uint32_t)'BGRA'),
            };
            surfaces[i] = IOSurfaceCreate((__bridge CFDictionaryRef)props);
            MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
                                                                                          width:width
                                                                                         height:height
                                                                                      mipmapped:NO];
            td.usage = MTLTextureUsageRenderTarget;
            td.storageMode = MTLStorageModeShared;
            textures[i] = [device newTextureWithDescriptor:td iosurface:surfaces[i] plane:0];
            hello.surfaces[i].name = IOSurfaceCreateMachPort(surfaces[i]);
            hello.surfaces[i].disposition = MACH_MSG_TYPE_MOVE_SEND;
            hello.surfaces[i].type = MACH_MSG_PORT_DESCRIPTOR;
        }
        for (uint32_t i = count; i < SPIKE_MAX_SURFACES; i++) {
            hello.surfaces[i].name = MACH_PORT_NULL;
            hello.surfaces[i].disposition = MACH_MSG_TYPE_COPY_SEND;
            hello.surfaces[i].type = MACH_MSG_PORT_DESCRIPTOR;
        }
        mach_port_t ticks = MACH_PORT_NULL;
        kr = mach_port_allocate(mach_task_self(), MACH_PORT_RIGHT_RECEIVE, &ticks);
        if (kr != KERN_SUCCESS) fail("mach_port_allocate", kr);
        hello.header.msgh_bits = MACH_MSGH_BITS_SET(MACH_MSG_TYPE_COPY_SEND, MACH_MSG_TYPE_MAKE_SEND, 0, MACH_MSGH_BITS_COMPLEX);
        hello.header.msgh_size = sizeof hello;
        hello.header.msgh_remote_port = consumer;
        hello.header.msgh_local_port = ticks;
        hello.header.msgh_id = SPIKE_MSG_HELLO;
        hello.body.msgh_descriptor_count = SPIKE_MAX_SURFACES;
        hello.count = count;
        hello.width = width;
        hello.height = height;
        kr = mach_msg(&hello.header, MACH_SEND_MSG, sizeof hello, 0, MACH_PORT_NULL, MACH_MSG_TIMEOUT_NONE, MACH_PORT_NULL);
        if (kr != KERN_SUCCESS) fail("hello", kr);

        mach_timebase_info_data_t tb;
        mach_timebase_info(&tb);
        uint64_t origin = mach_absolute_time();
        __block uint64_t seq = 0;
        __block uint32_t next = 0;
        __block uint32_t lastSent = UINT32_MAX;
        __block _Atomic uint64_t gpuNanos = 0;
        __block _Atomic uint32_t gpuFrames = 0;
        __block _Atomic uint32_t sendDrops = 0;
        __block uint32_t busySkips = 0;
        __block _Atomic int inflight = 0;

        dispatch_queue_t q = dispatch_queue_create("producer",
            dispatch_queue_attr_make_with_qos_class(DISPATCH_QUEUE_SERIAL, QOS_CLASS_USER_INTERACTIVE, 0));
        void (^render)(void) = ^{
            if (atomic_load(&inflight) > 0) {
                busySkips++;
                return;
            }
            uint32_t index = UINT32_MAX;
            for (uint32_t k = 0; k < count; k++) {
                uint32_t i = (next + k) % count;
                if (i != lastSent && !IOSurfaceIsInUse(surfaces[i])) {
                    index = i;
                    break;
                }
            }
            if (index == UINT32_MAX) {
                busySkips++;
                return;
            }
            next = (index + 1) % count;
            lastSent = index;
            uint64_t started = mach_absolute_time();
            if (getenv("SPIKE_TRACE"))
                fprintf(stderr, "abs %.3f producer start seq %llu\n", (double)started * tb.numer / tb.denom / 1e6, seq + 1);
            uint64_t s = ++seq;
            Uniforms u = {
                .t = (float)((double)(started - origin) * tb.numer / tb.denom / 1e9),
                .seq = (uint32_t)s,
                .size = {(float)width, (float)height},
            };
            MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
            rp.colorAttachments[0].texture = textures[index];
            rp.colorAttachments[0].loadAction = MTLLoadActionDontCare;
            rp.colorAttachments[0].storeAction = MTLStoreActionStore;
            id<MTLCommandBuffer> cb = [queue commandBuffer];
            id<MTLRenderCommandEncoder> enc = [cb renderCommandEncoderWithDescriptor:rp];
            [enc setRenderPipelineState:pipeline];
            [enc setFragmentBytes:&u length:sizeof u atIndex:0];
            [enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
            [enc endEncoding];
            atomic_fetch_add(&inflight, 1);
            [cb addCompletedHandler:^(id<MTLCommandBuffer> done) {
                spike_frame f = {0};
                f.header.msgh_bits = MACH_MSGH_BITS_SET(MACH_MSG_TYPE_COPY_SEND, 0, 0, 0);
                f.header.msgh_size = sizeof f;
                f.header.msgh_remote_port = consumer;
                f.header.msgh_id = SPIKE_MSG_FRAME;
                f.index = index;
                f.seq = s;
                f.started = started;
                f.ready = mach_absolute_time();
                kern_return_t sk = mach_msg(&f.header, MACH_SEND_MSG | MACH_SEND_TIMEOUT, sizeof f, 0, MACH_PORT_NULL, 0, MACH_PORT_NULL);
                if (sk == MACH_SEND_INVALID_DEST) exit(0);
                if (sk != KERN_SUCCESS) atomic_fetch_add(&sendDrops, 1);
                atomic_fetch_add(&gpuNanos, (uint64_t)((done.GPUEndTime - done.GPUStartTime) * 1e9));
                atomic_fetch_add(&gpuFrames, 1);
                atomic_fetch_sub(&inflight, 1);
            }];
            [cb commit];
        };

        dispatch_source_t source;
        dispatch_source_t deadline = dispatch_source_create(DISPATCH_SOURCE_TYPE_TIMER, 0, DISPATCH_TIMER_STRICT, q);
        dispatch_source_set_event_handler(deadline, render);
        dispatch_resume(deadline);
        if (paced) {
            source = dispatch_source_create(DISPATCH_SOURCE_TYPE_MACH_RECV, ticks, 0, q);
            dispatch_source_set_event_handler(source, ^{
                spike_tick_inbox in;
                uint64_t target = 0, sent = 0;
                while (mach_msg(&in.msg.header, MACH_RCV_MSG | MACH_RCV_TIMEOUT, 0, sizeof in, ticks, 0, MACH_PORT_NULL) == KERN_SUCCESS) {
                    target = in.msg.target;
                    sent = in.msg.sent;
                }
                if (getenv("SPIKE_TRACE") && target)
                    fprintf(stderr, "abs %.3f producer got tick (sent %.3f)\n", (double)mach_absolute_time() * tb.numer / tb.denom / 1e6,
                            (double)sent * tb.numer / tb.denom / 1e6);
                if (!target) return;
                uint64_t margin = (uint64_t)(marginMs * 1e6 * tb.denom / tb.numer);
                uint64_t now = mach_absolute_time();
                if (marginMs <= 0 || target <= now + margin) {
                    render();
                } else {
                    dispatch_source_set_timer(deadline, dispatch_time(DISPATCH_TIME_NOW, (int64_t)((target - margin - now) * tb.numer / tb.denom)),
                                              DISPATCH_TIME_FOREVER, 0);
                }
            });
        } else {
            source = dispatch_source_create(DISPATCH_SOURCE_TYPE_TIMER, 0, DISPATCH_TIMER_STRICT, q);
            dispatch_source_set_timer(source, dispatch_time(DISPATCH_TIME_NOW, 0), (uint64_t)(1e9 / rate), 0);
            dispatch_source_set_event_handler(source, render);
        }
        dispatch_resume(source);

        dispatch_source_t report = dispatch_source_create(DISPATCH_SOURCE_TYPE_TIMER, 0, 0, q);
        dispatch_source_set_timer(report, dispatch_time(DISPATCH_TIME_NOW, 2 * NSEC_PER_SEC), 2 * NSEC_PER_SEC, 0);
        dispatch_source_set_event_handler(report, ^{
            uint32_t frames = atomic_exchange(&gpuFrames, 0);
            uint64_t nanos = atomic_exchange(&gpuNanos, 0);
            struct rusage ru;
            getrusage(RUSAGE_SELF, &ru);
            fprintf(stderr, "producer: %u frames, gpu %.3f ms/frame, %u send drops, %u busy skips, cpu %.2fs total\n",
                    frames, frames ? nanos / 1e6 / frames : 0.0, atomic_exchange(&sendDrops, 0), busySkips,
                    ru.ru_utime.tv_sec + ru.ru_stime.tv_sec + (ru.ru_utime.tv_usec + ru.ru_stime.tv_usec) / 1e6);
            busySkips = 0;
        });
        dispatch_resume(report);

        dispatch_main();
    }
}
