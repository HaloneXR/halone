#import <Cocoa/Cocoa.h>
#import <IOSurface/IOSurface.h>
#import <Metal/Metal.h>
#include <mach/mach_time.h>
#include <servers/bootstrap.h>

#include <string>
#include <vector>

#include "include/cef_app.h"
#include "include/cef_application_mac.h"
#include "include/cef_browser.h"
#include "include/cef_client.h"
#include "include/cef_command_line.h"
#include "include/wrapper/cef_library_loader.h"
#include "producer_common.h"

@interface SpikeApplication : NSApplication <CefAppProtocol> {
    BOOL _handlingSendEvent;
}
@end

@implementation SpikeApplication
- (BOOL)isHandlingSendEvent {
    return _handlingSendEvent;
}
- (void)setHandlingSendEvent:(BOOL)handlingSendEvent {
    _handlingSendEvent = handlingSendEvent;
}
- (void)sendEvent:(NSEvent *)event {
    CefScopedSendingEvent scoper;
    [super sendEvent:event];
}
@end

namespace {

mach_timebase_info_data_t gTimebase;
mach_port_t gConsumer = MACH_PORT_NULL;
mach_port_t gInbox = MACH_PORT_NULL;
id<MTLDevice> gDevice;
id<MTLCommandQueue> gQueue;
double gMarginMs = 4;
uint32_t gSurfaceCount = 4;
dispatch_source_t gBeginTimer;

double millis(uint64_t ticks) { return (double)ticks * gTimebase.numer / gTimebase.denom / 1e6; }

struct Panel {
    uint32_t number = 0;
    int width = 0;
    int height = 0;
    std::string url;
    IOSurfaceRef surfaces[SPIKE_MAX_SURFACES] = {};
    __strong id<MTLTexture> textures[SPIKE_MAX_SURFACES];
    uint32_t next = 0;
    uint32_t lastSent = UINT32_MAX;
    uint64_t seq = 0;
    uint64_t begun = 0;
    CefRefPtr<CefBrowser> browser;
    uint32_t paints = 0;
    uint32_t busy = 0;
    bool resizing = false;
    producer::Window beginToPaint;
    producer::Window blit;
};

std::vector<Panel *> gPanels;

void sendOrQuit(mach_msg_header_t *header, mach_msg_size_t size) {
    kern_return_t kr = mach_msg(header, MACH_SEND_MSG | MACH_SEND_TIMEOUT, size, 0, MACH_PORT_NULL, 0, MACH_PORT_NULL);
    if (kr == MACH_SEND_INVALID_DEST) CefQuitMessageLoop();
}

void sendHello(Panel *p) {
    spike_hello hello = {};
    for (uint32_t i = 0; i < SPIKE_MAX_SURFACES; i++) {
        hello.surfaces[i].type = MACH_MSG_PORT_DESCRIPTOR;
        if (i < gSurfaceCount) {
            hello.surfaces[i].name = IOSurfaceCreateMachPort(p->surfaces[i]);
            hello.surfaces[i].disposition = MACH_MSG_TYPE_MOVE_SEND;
        } else {
            hello.surfaces[i].name = MACH_PORT_NULL;
            hello.surfaces[i].disposition = MACH_MSG_TYPE_COPY_SEND;
        }
    }
    hello.header.msgh_bits = MACH_MSGH_BITS_SET(MACH_MSG_TYPE_COPY_SEND, MACH_MSG_TYPE_MAKE_SEND, 0, MACH_MSGH_BITS_COMPLEX);
    hello.header.msgh_size = sizeof hello;
    hello.header.msgh_remote_port = gConsumer;
    hello.header.msgh_local_port = gInbox;
    hello.header.msgh_id = SPIKE_MSG_HELLO;
    hello.body.msgh_descriptor_count = SPIKE_MAX_SURFACES;
    hello.panel = p->number;
    hello.count = gSurfaceCount;
    hello.width = (uint32_t)p->width;
    hello.height = (uint32_t)p->height;
    kern_return_t kr = mach_msg(&hello.header, MACH_SEND_MSG, sizeof hello, 0, MACH_PORT_NULL, MACH_MSG_TIMEOUT_NONE, MACH_PORT_NULL);
    if (kr != KERN_SUCCESS) {
        fprintf(stderr, "producer: hello failed: %s\n", mach_error_string(kr));
        exit(1);
    }
}

void allocateSurfaces(Panel *p) {
    for (uint32_t i = 0; i < gSurfaceCount; i++) {
        NSDictionary *props = @{
            (id)kIOSurfaceWidth: @(p->width),
            (id)kIOSurfaceHeight: @(p->height),
            (id)kIOSurfaceBytesPerElement: @4,
            (id)kIOSurfacePixelFormat: @((uint32_t)'BGRA'),
        };
        p->surfaces[i] = IOSurfaceCreate((__bridge CFDictionaryRef)props);
        MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
                                                                                      width:(NSUInteger)p->width
                                                                                     height:(NSUInteger)p->height
                                                                                  mipmapped:NO];
        td.usage = MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget;
        td.storageMode = MTLStorageModeShared;
        p->textures[i] = [gDevice newTextureWithDescriptor:td iosurface:p->surfaces[i] plane:0];
    }
}

void deliver(Panel *p, const CefAcceleratedPaintInfo &info) {
    uint64_t painted = mach_absolute_time();
    p->paints++;
    if (p->begun) p->beginToPaint.add(millis(painted - p->begun));
    if (info.format != CEF_COLOR_TYPE_BGRA_8888) {
        static bool warned;
        if (!warned) fprintf(stderr, "producer: unexpected paint format %d\n", info.format);
        warned = true;
        return;
    }
    const cef_rect_t &visible = info.extra.visible_rect;
    if (p->resizing) {
        if (visible.width != p->width || visible.height != p->height) return;
        p->resizing = false;
    }
    uint32_t index = UINT32_MAX;
    for (uint32_t k = 0; k < gSurfaceCount; k++) {
        uint32_t i = (p->next + k) % gSurfaceCount;
        if (i != p->lastSent && !IOSurfaceIsInUse(p->surfaces[i])) {
            index = i;
            break;
        }
    }
    if (index == UINT32_MAX) {
        p->busy++;
        return;
    }
    IOSurfaceRef source = (IOSurfaceRef)info.shared_texture_io_surface;
    MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
                                                                                  width:IOSurfaceGetWidth(source)
                                                                                 height:IOSurfaceGetHeight(source)
                                                                              mipmapped:NO];
    td.usage = MTLTextureUsageShaderRead;
    td.storageMode = MTLStorageModeShared;
    id<MTLTexture> sourceTexture = [gDevice newTextureWithDescriptor:td iosurface:source plane:0];
    NSUInteger w = (NSUInteger)std::min(visible.width, p->width);
    NSUInteger h = (NSUInteger)std::min(visible.height, p->height);
    id<MTLCommandBuffer> cb = [gQueue commandBuffer];
    id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
    [blit copyFromTexture:sourceTexture
              sourceSlice:0
              sourceLevel:0
             sourceOrigin:MTLOriginMake((NSUInteger)visible.x, (NSUInteger)visible.y, 0)
               sourceSize:MTLSizeMake(w, h, 1)
                toTexture:p->textures[index]
         destinationSlice:0
         destinationLevel:0
        destinationOrigin:MTLOriginMake(0, 0, 0)];
    [blit endEncoding];
    [cb commit];
    [cb waitUntilCompleted];
    uint64_t ready = mach_absolute_time();
    p->blit.add(millis(ready - painted));
    p->next = (index + 1) % gSurfaceCount;
    p->lastSent = index;

    spike_frame f = {};
    f.header.msgh_bits = MACH_MSGH_BITS_SET(MACH_MSG_TYPE_COPY_SEND, 0, 0, 0);
    f.header.msgh_size = sizeof f;
    f.header.msgh_remote_port = gConsumer;
    f.header.msgh_id = SPIKE_MSG_FRAME;
    f.panel = p->number;
    f.index = index;
    f.seq = ++p->seq;
    f.begun = p->begun;
    f.painted = painted;
    f.ready = ready;
    sendOrQuit(&f.header, sizeof f);
}

void sendCursor(Panel *p, const char *name) {
    spike_cursor c = {};
    c.header.msgh_bits = MACH_MSGH_BITS_SET(MACH_MSG_TYPE_COPY_SEND, 0, 0, 0);
    c.header.msgh_size = sizeof c;
    c.header.msgh_remote_port = gConsumer;
    c.header.msgh_id = SPIKE_MSG_CURSOR;
    c.panel = p->number;
    strlcpy(c.name, name, sizeof c.name);
    sendOrQuit(&c.header, sizeof c);
}

class PanelClient : public CefClient, public CefRenderHandler, public CefLifeSpanHandler, public CefDisplayHandler {
public:
    explicit PanelClient(Panel *panel) : panel_(panel) {}

    CefRefPtr<CefRenderHandler> GetRenderHandler() override { return this; }
    CefRefPtr<CefLifeSpanHandler> GetLifeSpanHandler() override { return this; }
    CefRefPtr<CefDisplayHandler> GetDisplayHandler() override { return this; }

    void GetViewRect(CefRefPtr<CefBrowser>, CefRect &rect) override { rect = CefRect(0, 0, panel_->width, panel_->height); }

    void OnPaint(CefRefPtr<CefBrowser>, PaintElementType, const RectList &, const void *, int, int) override {
        static bool warned;
        if (!warned) fprintf(stderr, "producer: got a software paint; shared textures are not in use\n");
        warned = true;
    }

    void OnAcceleratedPaint(CefRefPtr<CefBrowser>, PaintElementType type, const RectList &,
                            const CefAcceleratedPaintInfo &info) override {
        if (type == PET_VIEW) deliver(panel_, info);
    }

    void OnAfterCreated(CefRefPtr<CefBrowser> browser) override { panel_->browser = browser; }

    bool OnCursorChange(CefRefPtr<CefBrowser>, CefCursorHandle, cef_cursor_type_t type, const CefCursorInfo &) override {
        const char *name = producer::cssCursor(type);
        if (name != cursor_) {
            cursor_ = name;
            sendCursor(panel_, name);
        }
        return true;
    }

    bool OnConsoleMessage(CefRefPtr<CefBrowser>, cef_log_severity_t, const CefString &message, const CefString &,
                          int) override {
        fprintf(stderr, "panel %u console: %s\n", panel_->number, message.ToString().c_str());
        return true;
    }

private:
    Panel *panel_;
    const char *cursor_ = "default";
    IMPLEMENT_REFCOUNTING(PanelClient);
};

class SpikeApp : public CefApp, public CefBrowserProcessHandler {
public:
    CefRefPtr<CefBrowserProcessHandler> GetBrowserProcessHandler() override { return this; }

    void OnBeforeCommandLineProcessing(const CefString &, CefRefPtr<CefCommandLine> commandLine) override {
        commandLine->AppendSwitchWithValue("autoplay-policy", "no-user-gesture-required");
        commandLine->AppendSwitchWithValue("remote-debugging-port", "9230");
        commandLine->AppendSwitchWithValue("remote-allow-origins", "*");
        // Every rebuild changes the binary's signature, and the real keychain asks again each time.
        commandLine->AppendSwitch("use-mock-keychain");
    }

    void OnContextInitialized() override {
        for (Panel *p : gPanels) {
            CefWindowInfo info;
            info.SetAsWindowless(nullptr);
            info.shared_texture_enabled = true;
            info.external_begin_frame_enabled = true;
            CefBrowserSettings settings;
            settings.windowless_frame_rate = 60;
            CefBrowserHost::CreateBrowser(info, new PanelClient(p), p->url, settings, nullptr, nullptr);
        }
    }

private:
    IMPLEMENT_REFCOUNTING(SpikeApp);
};

void focus(Panel *target) {
    for (Panel *p : gPanels)
        if (p->browser) p->browser->GetHost()->SetFocus(p == target);
}

void handleInput(const spike_input &in) {
    if (in.panel >= gPanels.size()) return;
    Panel *p = gPanels[in.panel];
    if (!p->browser) return;
    if (in.kind == SPIKE_INPUT_DOWN) focus(p);
    producer::dispatchInput(p->browser->GetHost(), in, [](const spike_input &key, CefKeyEvent &event) {
        event.native_key_code = (int)key.keyCode;
        event.character = key.character;
        event.unmodified_character = key.unmodified;
        return key.character && (key.character < 0xF700 || key.character > 0xF8FF);
    });
}

void handleResize(const spike_resize &r) {
    if (r.panel >= gPanels.size() || r.width == 0 || r.height == 0) return;
    Panel *p = gPanels[r.panel];
    if ((int)r.width == p->width && (int)r.height == p->height) return;
    for (uint32_t i = 0; i < gSurfaceCount; i++) {
        p->textures[i] = nil;
        if (p->surfaces[i]) CFRelease(p->surfaces[i]);
        p->surfaces[i] = nullptr;
    }
    p->width = (int)r.width;
    p->height = (int)r.height;
    p->next = 0;
    p->lastSent = UINT32_MAX;
    p->resizing = true;
    allocateSurfaces(p);
    sendHello(p);
    if (p->browser) p->browser->GetHost()->WasResized();
}

void beginFrame() {
    uint64_t now = mach_absolute_time();
    for (Panel *p : gPanels) {
        if (!p->browser) continue;
        p->begun = now;
        p->browser->GetHost()->SendExternalBeginFrame();
    }
}

void handleTick(const spike_tick &tick) {
    uint64_t margin = (uint64_t)(gMarginMs * 1e6 * gTimebase.denom / gTimebase.numer);
    uint64_t now = mach_absolute_time();
    if (tick.target <= now + margin) {
        beginFrame();
        return;
    }
    dispatch_source_set_timer(gBeginTimer,
                              dispatch_time(DISPATCH_TIME_NOW, (int64_t)((tick.target - margin - now) * gTimebase.numer / gTimebase.denom)),
                              DISPATCH_TIME_FOREVER, 0);
}

void drainInbox() {
    spike_tick latestTick = {};
    bool ticked = false;
    for (;;) {
        spike_producer_inbox in = {};
        kern_return_t kr = mach_msg(&in.header, MACH_RCV_MSG | MACH_RCV_TIMEOUT, 0, sizeof in, gInbox, 0, MACH_PORT_NULL);
        if (kr != KERN_SUCCESS) break;
        if (in.header.msgh_id == SPIKE_MSG_TICK) {
            latestTick = in.tick.msg;
            ticked = true;
        } else if (in.header.msgh_id == SPIKE_MSG_INPUT) {
            handleInput(in.input.msg);
        } else if (in.header.msgh_id == SPIKE_MSG_RESIZE) {
            handleResize(in.resize.msg);
        }
    }
    if (ticked) handleTick(latestTick);
}

void report() {
    for (Panel *p : gPanels) {
        fprintf(stderr, "producer: panel %u %u paints, begin->paint p50 %.2f p95 %.2f ms, blit p50 %.2f p95 %.2f ms, %u busy\n",
                p->number, p->paints, p->beginToPaint.percentile(0.5), p->beginToPaint.percentile(0.95),
                p->blit.percentile(0.5), p->blit.percentile(0.95), p->busy);
        p->paints = p->busy = 0;
        p->beginToPaint.values.clear();
        p->blit.values.clear();
    }
}

bool parsePanels(const std::string &spec) {
    std::vector<producer::PanelSpec> specs;
    if (!producer::parsePanels(spec, specs)) return false;
    for (const producer::PanelSpec &s : specs) {
        Panel *p = new Panel;
        p->number = s.number;
        p->width = s.width;
        p->height = s.height;
        p->url = s.url;
        gPanels.push_back(p);
    }
    return true;
}

}

int main(int argc, char *argv[]) {
    CefScopedLibraryLoader loader;
    if (!loader.LoadInMain()) return 1;
    CefMainArgs mainArgs(argc, argv);

    @autoreleasepool {
        [SpikeApplication sharedApplication];
        mach_timebase_info(&gTimebase);

        CefRefPtr<CefCommandLine> commandLine = CefCommandLine::CreateCommandLine();
        commandLine->InitFromArgv(argc, argv);
        std::string service = commandLine->GetSwitchValue("spike-service").ToString();
        if (service.empty() || !parsePanels(commandLine->GetSwitchValue("spike-panels").ToString())) {
            fprintf(stderr, "usage: producer --spike-service=<name> --spike-panels=WxH@url[,WxH@url...] [--spike-margin=ms] [--spike-surfaces=n]\n");
            return 2;
        }
        if (commandLine->HasSwitch("spike-margin")) gMarginMs = std::stod(commandLine->GetSwitchValue("spike-margin").ToString());
        if (commandLine->HasSwitch("spike-surfaces"))
            gSurfaceCount = (uint32_t)std::clamp(std::stoi(commandLine->GetSwitchValue("spike-surfaces").ToString()), 2, SPIKE_MAX_SURFACES);

        kern_return_t kr = bootstrap_look_up(bootstrap_port, service.c_str(), &gConsumer);
        if (kr != KERN_SUCCESS) {
            fprintf(stderr, "producer: bootstrap_look_up failed: %s\n", mach_error_string(kr));
            return 1;
        }
        mach_port_allocate(mach_task_self(), MACH_PORT_RIGHT_RECEIVE, &gInbox);

        gDevice = MTLCreateSystemDefaultDevice();
        gQueue = [gDevice newCommandQueue];
        for (Panel *p : gPanels) {
            allocateSurfaces(p);
            sendHello(p);
        }

        CefSettings settings;
        settings.no_sandbox = true;
        settings.windowless_rendering_enabled = true;
        settings.log_severity = LOGSEVERITY_ERROR;
        NSString *cache = [NSTemporaryDirectory() stringByAppendingPathComponent:@"halone-cef-spike"];
        CefString(&settings.root_cache_path) = cache.UTF8String;

        CefRefPtr<SpikeApp> app(new SpikeApp);
        if (!CefInitialize(mainArgs, settings, app.get(), nullptr)) return CefGetExitCode();

        gBeginTimer = dispatch_source_create(DISPATCH_SOURCE_TYPE_TIMER, 0, DISPATCH_TIMER_STRICT, dispatch_get_main_queue());
        dispatch_source_set_event_handler(gBeginTimer, ^{ beginFrame(); });
        dispatch_resume(gBeginTimer);

        dispatch_source_t inbox = dispatch_source_create(DISPATCH_SOURCE_TYPE_MACH_RECV, gInbox, 0, dispatch_get_main_queue());
        dispatch_source_set_event_handler(inbox, ^{ drainInbox(); });
        dispatch_resume(inbox);

        dispatch_source_t reporter = dispatch_source_create(DISPATCH_SOURCE_TYPE_TIMER, 0, 0, dispatch_get_main_queue());
        dispatch_source_set_timer(reporter, dispatch_time(DISPATCH_TIME_NOW, 2 * NSEC_PER_SEC), 2 * NSEC_PER_SEC, 0);
        dispatch_source_set_event_handler(reporter, ^{ report(); });
        dispatch_resume(reporter);

        dispatch_source_t term = dispatch_source_create(DISPATCH_SOURCE_TYPE_SIGNAL, SIGTERM, 0, dispatch_get_main_queue());
        signal(SIGTERM, SIG_IGN);
        dispatch_source_set_event_handler(term, ^{ CefQuitMessageLoop(); });
        dispatch_resume(term);

        CefRunMessageLoop();
        for (Panel *p : gPanels) p->browser = nullptr;
        CefShutdown();
    }
    return 0;
}
