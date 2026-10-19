#import <Cocoa/Cocoa.h>
#import <IOSurface/IOSurface.h>
#import <Metal/Metal.h>
#include <mach/mach_time.h>
#include <servers/bootstrap.h>

#include <map>
#include <string>
#include <vector>

#include "include/cef_app.h"
#include "include/cef_application_mac.h"
#include "include/cef_browser.h"
#include "include/cef_client.h"
#include "include/cef_command_line.h"
#include "include/cef_parser.h"
#include "include/cef_context_menu_handler.h"
#include "include/cef_download_handler.h"
#include "include/cef_permission_handler.h"
#include "include/cef_request_context.h"
#include "include/cef_scheme.h"
#include "include/cef_stream.h"
#include "include/wrapper/cef_stream_resource_handler.h"
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
const double DOWNLOAD_PROGRESS_MS = 250;
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
    std::string origin;
    std::string script;
    std::string root;
    bool isPrivate = false;
    producer::Page page;
    bool closing = false;
    producer::Window beginToPaint;
    producer::Window blit;
};

std::map<uint32_t, Panel *> gPanels;
bool gContextReady = false;
std::string gWebRoot;

Panel *findPanel(uint32_t number) {
    auto it = gPanels.find(number);
    return it == gPanels.end() || it->second->closing ? nullptr : it->second;
}

std::vector<Panel *> livePanels() {
    std::vector<Panel *> live;
    for (auto &entry : gPanels)
        if (!entry.second->closing) live.push_back(entry.second);
    return live;
}

void releaseSurfaces(Panel *p) {
    for (uint32_t i = 0; i < SPIKE_MAX_SURFACES; i++) {
        p->textures[i] = nil;
        if (p->surfaces[i]) CFRelease(p->surfaces[i]);
        p->surfaces[i] = nullptr;
    }
}

void sendOrQuit(mach_msg_header_t *header, mach_msg_size_t size) {
    kern_return_t kr = mach_msg(header, MACH_SEND_MSG | MACH_SEND_TIMEOUT, size, 0, MACH_PORT_NULL, 0, MACH_PORT_NULL);
    if (kr == MACH_SEND_INVALID_DEST) CefQuitMessageLoop();
}

void sendHello(Panel *p) {
    spike_hello hello = {};
    for (uint32_t i = 0; i < SPIKE_MAX_SURFACES; i++) {
        hello.surfaces[i].type = MACH_MSG_PORT_DESCRIPTOR;
        if (p && i < gSurfaceCount) {
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
    hello.panel = p ? p->number : SPIKE_NO_PANEL;
    hello.count = p ? gSurfaceCount : 0;
    hello.width = p ? (uint32_t)p->width : 0;
    hello.height = p ? (uint32_t)p->height : 0;
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

void sendPage(Panel *p, const producer::Page &page) {
    if (page == p->page) return;
    p->page = page;
    spike_page g = {};
    g.header.msgh_bits = MACH_MSGH_BITS_SET(MACH_MSG_TYPE_COPY_SEND, 0, 0, 0);
    g.header.msgh_size = sizeof g;
    g.header.msgh_remote_port = gConsumer;
    g.header.msgh_id = SPIKE_MSG_PAGE;
    g.panel = p->number;
    g.flags = page.flags;
    strlcpy(g.title, page.title.c_str(), sizeof g.title);
    strlcpy(g.url, page.url.c_str(), sizeof g.url);
    sendOrQuit(&g.header, sizeof g);
}

void sendPopup(Panel *p, const std::string &url, bool background) {
    spike_popup u = {};
    u.header.msgh_bits = MACH_MSGH_BITS_SET(MACH_MSG_TYPE_COPY_SEND, 0, 0, 0);
    u.header.msgh_size = sizeof u;
    u.header.msgh_remote_port = gConsumer;
    u.header.msgh_id = SPIKE_MSG_POPUP;
    u.panel = p->number;
    u.background = background ? 1 : 0;
    strlcpy(u.url, url.c_str(), sizeof u.url);
    sendOrQuit(&u.header, sizeof u);
}

template <typename Message>
void sendMessage(Message &m, mach_msg_id_t id) {
    m.header.msgh_bits = MACH_MSGH_BITS_SET(MACH_MSG_TYPE_COPY_SEND, 0, 0, 0);
    m.header.msgh_size = sizeof m;
    m.header.msgh_remote_port = gConsumer;
    m.header.msgh_id = id;
    sendOrQuit(&m.header, sizeof m);
}

void sendContext(Panel *p, int x, int y, const producer::Context &context) {
    spike_context c = {};
    c.panel = p->number;
    c.flags = context.flags;
    c.media = context.media;
    c.x = (float)x;
    c.y = (float)y;
    strlcpy(c.link, context.link.c_str(), sizeof c.link);
    strlcpy(c.source, context.source.c_str(), sizeof c.source);
    strlcpy(c.selection, context.selection.c_str(), sizeof c.selection);
    sendMessage(c, SPIKE_MSG_CONTEXT);
}

// Downloads in flight, by CEF's id: the callback that says where one goes, until it is told, and the one that stops it.
struct Download {
    uint32_t panel = 0;
    CefRefPtr<CefBeforeDownloadCallback> before;
    CefRefPtr<CefDownloadItemCallback> item;
    uint64_t lastSent = 0;
    uint32_t lastState = 0;
};
std::map<uint32_t, Download> gDownloads;

void sendDownload(uint32_t panel, CefRefPtr<CefDownloadItem> item, uint32_t state, const std::string &name) {
    spike_download d = {};
    d.panel = panel;
    d.download = item->GetId();
    d.state = state;
    d.received = item->GetReceivedBytes();
    d.total = item->GetTotalBytes();
    strlcpy(d.url, item->GetURL().ToString().c_str(), sizeof d.url);
    strlcpy(d.name, name.c_str(), sizeof d.name);
    strlcpy(d.mime, item->GetMimeType().ToString().c_str(), sizeof d.mime);
    strlcpy(d.path, item->GetFullPath().ToString().c_str(), sizeof d.path);
    sendMessage(d, SPIKE_MSG_DOWNLOAD);
}

void handleDownloadControl(const spike_download_control &c) {
    auto it = gDownloads.find(c.download);
    if (it == gDownloads.end()) return;
    Download &d = it->second;
    std::string path = producer::text(c.path, sizeof c.path);
    if (c.cancel) {
        if (d.item) d.item->Cancel();
        d.before = nullptr;
        if (!d.item) gDownloads.erase(it);
        return;
    }
    if (d.before && !path.empty()) {
        d.before->Continue(path, false);
        d.before = nullptr;
    }
}

// Permission requests waiting on the wearer, by the number given them here.
struct Prompt {
    uint32_t panel = 0;
    std::string kind;
    std::string origin;
    uint64_t cefId = 0;
    CefRefPtr<CefPermissionPromptCallback> permission;
    CefRefPtr<CefMediaAccessCallback> media;
    uint32_t mediaRequested = 0;
    std::string popup;
    bool popupBackground = false;
};
std::map<uint32_t, Prompt> gPrompts;
uint32_t gNextPrompt = 1;

void sendPrompt(uint32_t panel, uint32_t id, const std::string &kind, const std::string &host, uint32_t flags) {
    spike_prompt q = {};
    q.panel = panel;
    q.prompt = id;
    q.flags = flags;
    strlcpy(q.kind, kind.c_str(), sizeof q.kind);
    strlcpy(q.host, host.c_str(), sizeof q.host);
    sendMessage(q, SPIKE_MSG_PROMPT);
}

uint32_t ask(Panel *p, Prompt prompt, bool rememberable) {
    uint32_t id = gNextPrompt++;
    prompt.panel = p->number;
    sendPrompt(p->number, id, prompt.kind, producer::hostOf(prompt.origin), rememberable ? SPIKE_PROMPT_REMEMBERABLE : 0);
    gPrompts[id] = prompt;
    return id;
}

void answer(Prompt &prompt, bool allow, bool remember);

/** Denies what [panel]'s page still waits on, as Android does when a page starts or its tab closes. */
void denyPrompts(uint32_t panel) {
    for (auto it = gPrompts.begin(); it != gPrompts.end();) {
        if (it->second.panel != panel) {
            ++it;
            continue;
        }
        answer(it->second, false, false);
        sendPrompt(panel, it->first, "", "", SPIKE_PROMPT_GONE);
        it = gPrompts.erase(it);
    }
}

void sendFullscreen(Panel *p, bool fullscreen) {
    spike_fullscreen f = {};
    f.panel = p->number;
    f.fullscreen = fullscreen ? 1 : 0;
    sendMessage(f, SPIKE_MSG_FULLSCREEN);
}

void sendScroll(Panel *p, double y) {
    spike_scroll c = {};
    c.panel = p->number;
    c.y = (float)y;
    sendMessage(c, SPIKE_MSG_SCROLL);
}

class PanelClient : public CefClient, public CefRenderHandler, public CefLifeSpanHandler, public CefDisplayHandler, public CefLoadHandler,
                    public CefContextMenuHandler, public CefDownloadHandler, public CefPermissionHandler {
public:
    explicit PanelClient(Panel *panel) : panel_(panel) {}

    CefRefPtr<CefRenderHandler> GetRenderHandler() override { return this; }
    CefRefPtr<CefLifeSpanHandler> GetLifeSpanHandler() override { return this; }
    CefRefPtr<CefDisplayHandler> GetDisplayHandler() override { return this; }
    CefRefPtr<CefLoadHandler> GetLoadHandler() override { return this; }
    CefRefPtr<CefContextMenuHandler> GetContextMenuHandler() override { return this; }
    CefRefPtr<CefDownloadHandler> GetDownloadHandler() override { return this; }
    CefRefPtr<CefPermissionHandler> GetPermissionHandler() override { return this; }

    // The shell shows the menu, as Android's PageMenu does; Chromium's own has nowhere to appear.
    bool RunContextMenu(CefRefPtr<CefBrowser>, CefRefPtr<CefFrame>, CefRefPtr<CefContextMenuParams> params,
                        CefRefPtr<CefMenuModel>, CefRefPtr<CefRunContextMenuCallback> callback) override {
        if (panel_) sendContext(panel_, params->GetXCoord(), params->GetYCoord(), producer::contextOf(params));
        callback->Cancel();
        return true;
    }

    bool OnBeforeDownload(CefRefPtr<CefBrowser>, CefRefPtr<CefDownloadItem> item, const CefString &suggested,
                          CefRefPtr<CefBeforeDownloadCallback> callback) override {
        if (!panel_) return false;
        Download &d = gDownloads[item->GetId()];
        d.panel = panel_->number;
        d.before = callback;
        d.lastState = SPIKE_DOWNLOAD_STARTED;
        sendDownload(panel_->number, item, SPIKE_DOWNLOAD_STARTED, suggested.ToString());
        return true;
    }

    void OnDownloadUpdated(CefRefPtr<CefBrowser>, CefRefPtr<CefDownloadItem> item, CefRefPtr<CefDownloadItemCallback> callback) override {
        auto it = gDownloads.find(item->GetId());
        if (it == gDownloads.end() || it->second.before) return;
        Download &d = it->second;
        d.item = callback;
        uint32_t state = producer::downloadState(item);
        uint64_t now = mach_absolute_time();
        bool final = state != SPIKE_DOWNLOAD_PROGRESS;
        if (!final && d.lastState == SPIKE_DOWNLOAD_PROGRESS && millis(now - d.lastSent) < DOWNLOAD_PROGRESS_MS) return;
        d.lastSent = now;
        d.lastState = state;
        std::string path = item->GetFullPath().ToString();
        sendDownload(d.panel, item, state, path.substr(path.find_last_of('/') + 1));
        if (final) gDownloads.erase(it);
    }

    bool OnShowPermissionPrompt(CefRefPtr<CefBrowser>, uint64_t promptId, const CefString &origin, uint32_t requested,
                                CefRefPtr<CefPermissionPromptCallback> callback) override {
        std::string kind = producer::permissionKind(requested);
        if (!panel_ || kind.empty()) {
            callback->Continue(CEF_PERMISSION_RESULT_DENY);
            return true;
        }
        Prompt prompt;
        prompt.kind = kind;
        prompt.origin = origin.ToString();
        prompt.cefId = promptId;
        prompt.permission = callback;
        ask(panel_, prompt, kind != "clipboard" && !panel_->isPrivate);
        return true;
    }

    void OnDismissPermissionPrompt(CefRefPtr<CefBrowser>, uint64_t promptId, cef_permission_request_result_t) override {
        for (auto it = gPrompts.begin(); it != gPrompts.end(); ++it) {
            if (it->second.cefId != promptId || !it->second.permission) continue;
            sendPrompt(it->second.panel, it->first, "", "", SPIKE_PROMPT_GONE);
            gPrompts.erase(it);
            return;
        }
    }

    // The microphone alone, as on Android; a camera is refused.
    bool OnRequestMediaAccessPermission(CefRefPtr<CefBrowser>, CefRefPtr<CefFrame>, const CefString &origin,
                                        uint32_t requested, CefRefPtr<CefMediaAccessCallback> callback) override {
        uint32_t video = CEF_MEDIA_PERMISSION_DEVICE_VIDEO_CAPTURE | CEF_MEDIA_PERMISSION_DESKTOP_VIDEO_CAPTURE;
        if (!panel_ || (requested & video) || !(requested & CEF_MEDIA_PERMISSION_DEVICE_AUDIO_CAPTURE)) {
            callback->Cancel();
            return true;
        }
        Prompt prompt;
        prompt.kind = "microphone";
        prompt.origin = origin.ToString();
        prompt.media = callback;
        prompt.mediaRequested = requested;
        ask(panel_, prompt, false);
        return true;
    }

    void OnLoadStart(CefRefPtr<CefBrowser>, CefRefPtr<CefFrame> frame, TransitionType) override {
        if (panel_ && frame->IsMain()) denyPrompts(panel_->number);
    }

    void OnFullscreenModeChange(CefRefPtr<CefBrowser>, bool fullscreen) override {
        if (panel_) sendFullscreen(panel_, fullscreen);
    }

    void OnScrollOffsetChanged(CefRefPtr<CefBrowser>, double, double y) override {
        if (panel_) sendScroll(panel_, y);
    }

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

    void OnAfterCreated(CefRefPtr<CefBrowser> browser) override {
        panel_->browser = browser;
        if (panel_->closing) browser->GetHost()->CloseBrowser(true);
    }

    void OnBeforeClose(CefRefPtr<CefBrowser>) override {
        panel_->browser = nullptr;
        if (!panel_->closing) return;
        gPanels.erase(panel_->number);
        releaseSurfaces(panel_);
        delete panel_;
        panel_ = nullptr;
    }

    void OnLoadEnd(CefRefPtr<CefBrowser>, CefRefPtr<CefFrame> frame, int) override {
        if (panel_) producer::injectBridge(frame, panel_->origin, panel_->script);
    }

    void OnTitleChange(CefRefPtr<CefBrowser>, const CefString &title) override {
        if (!panel_) return;
        producer::Page page = panel_->page;
        page.title = title.ToString();
        sendPage(panel_, page);
    }

    void OnAddressChange(CefRefPtr<CefBrowser>, CefRefPtr<CefFrame> frame, const CefString &url) override {
        if (!panel_ || !frame->IsMain()) return;
        producer::Page page = panel_->page;
        page.url = url.ToString();
        sendPage(panel_, page);
    }

    void OnLoadingStateChange(CefRefPtr<CefBrowser>, bool isLoading, bool canGoBack, bool canGoForward) override {
        if (!panel_) return;
        producer::Page page = panel_->page;
        page.flags = producer::pageFlags(isLoading, canGoBack, canGoForward);
        sendPage(panel_, page);
    }

    // A new window or tab is the compositor's to open, as a tab of the same window. One the wearer did not ask for
    // waits on a popup prompt, as Gecko's popup blocker makes it on Android.
    bool OnBeforePopup(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame>, int, const CefString &url, const CefString &,
                       WindowOpenDisposition disposition, bool userGesture, const CefPopupFeatures &, CefWindowInfo &,
                       CefRefPtr<CefClient> &, CefBrowserSettings &, CefRefPtr<CefDictionaryValue> &, bool *) override {
        if (!panel_) return true;
        if (userGesture) {
            sendPopup(panel_, url.ToString(), producer::inBackground(disposition));
            return true;
        }
        Prompt prompt;
        prompt.kind = "popup";
        prompt.origin = browser->GetMainFrame()->GetURL().ToString();
        prompt.popup = url.ToString();
        prompt.popupBackground = producer::inBackground(disposition);
        ask(panel_, prompt, false);
        return true;
    }

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


void createBrowser(Panel *p) {
    producer::serveOrigin(p->origin, p->root);
    producer::allowBridge(p->origin, p->script);
    CefWindowInfo info;
    info.SetAsWindowless(nullptr);
    info.shared_texture_enabled = true;
    info.external_begin_frame_enabled = true;
    CefBrowserSettings settings;
    settings.windowless_frame_rate = 60;
    CefBrowserHost::CreateBrowser(info, new PanelClient(p), p->url, settings, nullptr, producer::contextFor(p->isPrivate));
}

class SpikeApp : public CefApp, public CefBrowserProcessHandler {
public:
    CefRefPtr<CefBrowserProcessHandler> GetBrowserProcessHandler() override { return this; }

    void OnBeforeCommandLineProcessing(const CefString &, CefRefPtr<CefCommandLine> commandLine) override {
        producer::commonSwitches(commandLine);
        // Every rebuild changes the binary's signature, and the real keychain asks again each time.
        commandLine->AppendSwitch("use-mock-keychain");
    }

    void OnContextInitialized() override {
        producer::serveWebRoot(gWebRoot);
        gContextReady = true;
        for (Panel *p : livePanels()) createBrowser(p);
    }

private:
    IMPLEMENT_REFCOUNTING(SpikeApp);
};

void focus(Panel *target) {
    for (Panel *p : livePanels())
        if (p->browser) p->browser->GetHost()->SetFocus(p == target);
}

void handleInput(const spike_input &in) {
    Panel *p = findPanel(in.panel);
    if (!p) return;
    if (!p->browser) return;
    if (in.kind == SPIKE_INPUT_DOWN || in.kind == SPIKE_INPUT_FOCUS) focus(p);
    if (in.kind == SPIKE_INPUT_FOCUS) return;
    producer::dispatchInput(p->browser->GetHost(), in, [](const spike_input &key, CefKeyEvent &event) {
        event.native_key_code = (int)key.keyCode;
        event.character = key.character;
        event.unmodified_character = key.unmodified;
        return key.character && (key.character < 0xF700 || key.character > 0xF8FF);
    });
}

void handleResize(const spike_resize &r) {
    Panel *p = findPanel(r.panel);
    if (!p || r.width == 0 || r.height == 0) return;
    if ((int)r.width == p->width && (int)r.height == p->height) return;
    releaseSurfaces(p);
    p->width = (int)r.width;
    p->height = (int)r.height;
    p->next = 0;
    p->lastSent = UINT32_MAX;
    p->resizing = true;
    allocateSurfaces(p);
    sendHello(p);
    if (p->browser) p->browser->GetHost()->WasResized();
}

void handleOpen(const spike_open &o) {
    if (gPanels.count(o.panel) || o.width == 0 || o.height == 0) return;
    Panel *p = new Panel;
    p->number = o.panel;
    p->width = (int)o.width;
    p->height = (int)o.height;
    p->url = producer::text(o.url, sizeof o.url);
    p->origin = producer::text(o.origin, sizeof o.origin);
    p->script = producer::text(o.script, sizeof o.script);
    p->root = producer::text(o.root, sizeof o.root);
    p->isPrivate = (o.flags & SPIKE_OPEN_PRIVATE) != 0;
    gPanels[p->number] = p;
    allocateSurfaces(p);
    sendHello(p);
    if (gContextReady) createBrowser(p);
}

void answer(Prompt &prompt, bool allow, bool remember) {
    if (prompt.permission) {
        prompt.permission->Continue(allow ? CEF_PERMISSION_RESULT_ACCEPT : CEF_PERMISSION_RESULT_DENY);
        cef_content_setting_types_t type;
        if (remember && producer::rememberedAs(prompt.kind, type)) {
            CefRequestContext::GetGlobalContext()->SetContentSetting(prompt.origin, prompt.origin, type,
                allow ? CEF_CONTENT_SETTING_VALUE_ALLOW : CEF_CONTENT_SETTING_VALUE_BLOCK);
        }
    } else if (prompt.media) {
        if (allow) prompt.media->Continue(prompt.mediaRequested); else prompt.media->Cancel();
    } else if (!prompt.popup.empty() && allow) {
        if (Panel *p = findPanel(prompt.panel)) sendPopup(p, prompt.popup, prompt.popupBackground);
    }
}

void handlePromptAnswer(const spike_prompt_answer &a) {
    auto it = gPrompts.find(a.prompt);
    if (it == gPrompts.end() || it->second.panel != a.panel) return;
    answer(it->second, a.allow != 0, a.remember != 0);
    gPrompts.erase(it);
}

void handleCommand(const spike_command &c) {
    Panel *p = findPanel(c.panel);
    if (p && p->browser) producer::runCommand(p->browser, c.kind, producer::text(c.text, sizeof c.text));
}

void handleClose(const spike_close &c) {
    Panel *p = findPanel(c.panel);
    if (!p) return;
    denyPrompts(p->number);
    p->closing = true;
    if (p->browser) p->browser->GetHost()->CloseBrowser(true);
}

void beginFrame() {
    uint64_t now = mach_absolute_time();
    for (Panel *p : livePanels()) {
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
        } else if (in.header.msgh_id == SPIKE_MSG_OPEN) {
            handleOpen(in.open.msg);
        } else if (in.header.msgh_id == SPIKE_MSG_CLOSE) {
            handleClose(in.close.msg);
        } else if (in.header.msgh_id == SPIKE_MSG_COMMAND) {
            handleCommand(in.command.msg);
        } else if (in.header.msgh_id == SPIKE_MSG_DOWNLOAD_CONTROL) {
            handleDownloadControl(in.downloadControl.msg);
        } else if (in.header.msgh_id == SPIKE_MSG_PROMPT_ANSWER) {
            handlePromptAnswer(in.promptAnswer.msg);
        }
    }
    if (ticked) handleTick(latestTick);
}

void report() {
    for (Panel *p : livePanels()) {
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
    if (spec.empty()) return true;
    if (!producer::parsePanels(spec, specs)) return false;
    for (const producer::PanelSpec &s : specs) {
        Panel *p = new Panel;
        p->number = s.number;
        p->width = s.width;
        p->height = s.height;
        p->url = s.url;
        gPanels[p->number] = p;
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
            fprintf(stderr, "usage: producer --spike-service=<name> [--spike-panels=WxH@url[,WxH@url...]] [--spike-margin=ms] [--spike-surfaces=n] [--halone-web-root=dir]\n");
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
        gWebRoot = commandLine->GetSwitchValue("halone-web-root").ToString();
        sendHello(nullptr);
        for (Panel *p : livePanels()) {
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
        for (Panel *p : livePanels()) p->browser = nullptr;
        CefShutdown();
    }
    return 0;
}
