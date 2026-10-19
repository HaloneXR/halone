#pragma once

#include <algorithm>
#include <cstring>
#include <sstream>
#include <string>
#include <vector>

#include "include/cef_browser.h"
#include "include/cef_parser.h"
#include "include/cef_request_context.h"
#include "include/cef_scheme.h"
#include "include/wrapper/cef_stream_resource_handler.h"
#include "protocol.h"

// What the macOS and Linux producers share: everything but surfaces, the GPU copy and the transport.
namespace producer {

struct PanelSpec {
    uint32_t number = 0;
    int width = 0;
    int height = 0;
    std::string url;
};

inline bool parsePanels(const std::string &spec, std::vector<PanelSpec> &out) {
    std::stringstream list(spec);
    std::string item;
    while (std::getline(list, item, ',')) {
        size_t x = item.find('x');
        size_t at = item.find('@');
        if (x == std::string::npos || at == std::string::npos || x > at) return false;
        PanelSpec p;
        p.number = (uint32_t)out.size();
        p.width = std::stoi(item.substr(0, x));
        p.height = std::stoi(item.substr(x + 1, at - x - 1));
        p.url = item.substr(at + 1);
        out.push_back(p);
    }
    return !out.empty();
}

struct Window {
    std::vector<double> values;
    void add(double v) { values.push_back(v); }
    double percentile(double p) {
        if (values.empty()) return 0;
        std::sort(values.begin(), values.end());
        return values[std::min(values.size() - 1, (size_t)(values.size() * p))];
    }
};

/** Modifiers travel as AppKit's flags on every platform; the held mouse buttons are added here. */
inline uint32_t cefModifiers(uint32_t flags, uint32_t buttons) {
    uint32_t m = 0;
    if (flags & (1u << 17)) m |= EVENTFLAG_SHIFT_DOWN;
    if (flags & (1u << 18)) m |= EVENTFLAG_CONTROL_DOWN;
    if (flags & (1u << 19)) m |= EVENTFLAG_ALT_DOWN;
    if (flags & (1u << 20)) m |= EVENTFLAG_COMMAND_DOWN;
    if (flags & (1u << 16)) m |= EVENTFLAG_CAPS_LOCK_ON;
    if (flags & SPIKE_MODIFIER_REPEAT) m |= EVENTFLAG_IS_REPEAT;
    if (buttons & 1) m |= EVENTFLAG_LEFT_MOUSE_BUTTON;
    if (buttons & 2) m |= EVENTFLAG_RIGHT_MOUSE_BUTTON;
    if (buttons & 4) m |= EVENTFLAG_MIDDLE_MOUSE_BUTTON;
    return m;
}

inline const char *cssCursor(cef_cursor_type_t type) {
    switch (type) {
        case CT_CROSS: return "crosshair";
        case CT_HAND: return "pointer";
        case CT_IBEAM: return "text";
        case CT_WAIT: return "wait";
        case CT_HELP: return "help";
        case CT_EASTRESIZE:
        case CT_WESTRESIZE:
        case CT_EASTWESTRESIZE:
        case CT_COLUMNRESIZE:
        case CT_MIDDLE_PANNING_HORIZONTAL: return "ew-resize";
        case CT_NORTHRESIZE:
        case CT_SOUTHRESIZE:
        case CT_NORTHSOUTHRESIZE:
        case CT_ROWRESIZE:
        case CT_MIDDLE_PANNING_VERTICAL: return "ns-resize";
        case CT_NORTHEASTRESIZE:
        case CT_SOUTHWESTRESIZE:
        case CT_NORTHEASTSOUTHWESTRESIZE: return "nesw-resize";
        case CT_NORTHWESTRESIZE:
        case CT_SOUTHEASTRESIZE:
        case CT_NORTHWESTSOUTHEASTRESIZE: return "nwse-resize";
        case CT_MIDDLEPANNING:
        case CT_EASTPANNING:
        case CT_NORTHPANNING:
        case CT_NORTHEASTPANNING:
        case CT_NORTHWESTPANNING:
        case CT_SOUTHPANNING:
        case CT_SOUTHEASTPANNING:
        case CT_SOUTHWESTPANNING:
        case CT_WESTPANNING:
        case CT_MOVE: return "all-scroll";
        case CT_VERTICALTEXT: return "vertical-text";
        case CT_CELL: return "cell";
        case CT_CONTEXTMENU: return "context-menu";
        case CT_ALIAS:
        case CT_DND_LINK: return "alias";
        case CT_PROGRESS: return "progress";
        case CT_NODROP:
        case CT_DND_NONE: return "not-allowed";
        case CT_COPY:
        case CT_DND_COPY: return "copy";
        case CT_NONE: return "none";
        case CT_NOTALLOWED: return "not-allowed";
        case CT_ZOOMIN: return "zoom-in";
        case CT_ZOOMOUT: return "zoom-out";
        case CT_GRAB: return "grab";
        case CT_GRABBING:
        case CT_DND_MOVE: return "grabbing";
        default: return "default";
    }
}

/**
 * Sends one input message to [host]. [fillKey] turns the message's key code and
 * characters into a CefKeyEvent the platform's CEF understands, and says whether
 * the key also types a character.
 */
template <typename FillKey>
void dispatchInput(CefRefPtr<CefBrowserHost> host, const spike_input &in, FillKey fillKey) {
    CefMouseEvent mouse;
    mouse.x = (int)in.x;
    mouse.y = (int)in.y;
    switch (in.kind) {
        case SPIKE_INPUT_MOVE:
            mouse.modifiers = cefModifiers(in.modifiers, in.button);
            host->SendMouseMoveEvent(mouse, false);
            break;
        case SPIKE_INPUT_LEAVE:
            host->SendMouseMoveEvent(mouse, true);
            break;
        case SPIKE_INPUT_DOWN:
        case SPIKE_INPUT_UP: {
            cef_mouse_button_type_t button = in.button == 1 ? MBT_RIGHT : in.button == 2 ? MBT_MIDDLE : MBT_LEFT;
            mouse.modifiers = cefModifiers(in.modifiers, in.kind == SPIKE_INPUT_DOWN ? 1u << in.button : 0);
            host->SendMouseClickEvent(mouse, button, in.kind == SPIKE_INPUT_UP, (int)in.clicks);
            break;
        }
        case SPIKE_INPUT_WHEEL:
            mouse.modifiers = cefModifiers(in.modifiers, 0);
            host->SendMouseWheelEvent(mouse, (int)in.dx, (int)in.dy);
            break;
        case SPIKE_INPUT_KEY_DOWN:
        case SPIKE_INPUT_KEY_UP: {
            CefKeyEvent key;
            key.modifiers = cefModifiers(in.modifiers, 0);
            bool types = fillKey(in, key);
            if (in.kind == SPIKE_INPUT_KEY_UP) {
                key.type = KEYEVENT_KEYUP;
                host->SendKeyEvent(key);
                break;
            }
            key.type = KEYEVENT_RAWKEYDOWN;
            host->SendKeyEvent(key);
            if (types) {
                key.type = KEYEVENT_CHAR;
                host->SendKeyEvent(key);
            }
            break;
        }
    }
}

/** Serves https://halone.local/ from a directory, as WebViewAssetLoader serves the APK's assets on Android. */
class WebRootHandlerFactory : public CefSchemeHandlerFactory {
public:
    explicit WebRootHandlerFactory(std::string root) : root_(std::move(root)) {
        while (!root_.empty() && root_.back() == '/') root_.pop_back();
    }

    CefRefPtr<CefResourceHandler> Create(CefRefPtr<CefBrowser>, CefRefPtr<CefFrame>, const CefString &,
                                         CefRefPtr<CefRequest> request) override {
        CefURLParts parts;
        if (!CefParseURL(request->GetURL(), parts)) return nullptr;
        std::string path = CefURIDecode(CefString(&parts.path), false, UU_SPACES).ToString();
        if (path.empty() || path.back() == '/') path += "index.html";
        if (path.find("..") != std::string::npos) return nullptr;
        CefRefPtr<CefStreamReader> stream = CefStreamReader::CreateForFile(root_ + path);
        if (!stream) {
            // A null stream crashes CefStreamResourceHandler::Read.
            static const char notFound[] = "Not Found";
            return new CefStreamResourceHandler(404, "Not Found", "text/plain", {},
                                                CefStreamReader::CreateForData((void *)notFound, sizeof notFound - 1));
        }
        size_t dot = path.rfind('.');
        std::string mime = dot == std::string::npos ? "" : CefGetMimeType(path.substr(dot + 1)).ToString();
        return new CefStreamResourceHandler(mime.empty() ? "application/octet-stream" : mime, stream);
    }

private:
    std::string root_;
    IMPLEMENT_REFCOUNTING(WebRootHandlerFactory);
};

inline void serveWebRoot(const std::string &root) {
    if (!root.empty()) CefRegisterSchemeHandlerFactory("https", "halone.local", new WebRootHandlerFactory(root));
}

// A Halone package's own origin, https://<id>.apps.halone.local, served from its files.
inline void serveOrigin(const std::string &origin, const std::string &root) {
    const std::string https = "https://";
    if (root.empty() || origin.compare(0, https.size(), https) != 0) return;
    CefRegisterSchemeHandlerFactory("https", origin.substr(https.size()), new WebRootHandlerFactory(root));
}

/**
 * The bridge is a WebSocket to the compositor on 127.0.0.1, which Local Network
 * Access blocks without asking in a windowless browser. Only the origin the
 * bridge was given to may reach it.
 */
inline void allowBridge(const std::string &origin, const std::string &script) {
    if (script.empty() || origin.empty()) return;
    CefRequestContext::GetGlobalContext()->SetContentSetting(origin, origin, CEF_CONTENT_SETTING_TYPE_LOOPBACK_NETWORK,
                                                             CEF_CONTENT_SETTING_VALUE_ALLOW);
}

/** As on Android, the bridge goes only to pages on the window's own origin, after each load of its main frame. */
inline void injectBridge(CefRefPtr<CefFrame> frame, const std::string &origin, const std::string &script) {
    if (!frame->IsMain() || script.empty() || origin.empty()) return;
    std::string url = frame->GetURL().ToString();
    if (url != origin && url.compare(0, origin.size() + 1, origin + "/") != 0) return;
    frame->ExecuteJavaScript(script, url, 0);
}

// What both producers ask of Chromium: media that plays without a gesture, DevTools on 9230 for tests, and
// overlay scrollbars, drawn over the page only while it scrolls, as on the glasses' phone.
inline void commonSwitches(CefRefPtr<CefCommandLine> commandLine) {
    commandLine->AppendSwitchWithValue("autoplay-policy", "no-user-gesture-required");
    commandLine->AppendSwitchWithValue("remote-debugging-port", "9230");
    commandLine->AppendSwitchWithValue("remote-allow-origins", "*");
    commandLine->AppendSwitchWithValue("enable-features", "OverlayScrollbar");
}

inline std::string text(const char *field, size_t size) { return std::string(field, strnlen(field, size)); }

// Where a panel's page is, as last told to the compositor, so only changes are sent.
struct Page {
    std::string title;
    std::string url;
    uint32_t flags = 0;

    bool operator==(const Page &other) const { return title == other.title && url == other.url && flags == other.flags; }
};

inline uint32_t pageFlags(bool loading, bool canGoBack, bool canGoForward) {
    return (loading ? SPIKE_PAGE_LOADING : 0) | (canGoBack ? SPIKE_PAGE_CAN_GO_BACK : 0) | (canGoForward ? SPIKE_PAGE_CAN_GO_FORWARD : 0);
}

inline void runCommand(CefRefPtr<CefBrowser> browser, uint32_t kind, const std::string &text) {
    switch (kind) {
        case SPIKE_COMMAND_NAVIGATE: browser->GetMainFrame()->LoadURL(text); break;
        case SPIKE_COMMAND_BACK: browser->GoBack(); break;
        case SPIKE_COMMAND_FORWARD: browser->GoForward(); break;
        case SPIKE_COMMAND_RELOAD: browser->Reload(); break;
        case SPIKE_COMMAND_STOP: browser->StopLoad(); break;
    }
}

// A private panel's profile: in memory, its own, gone with it. Null for the shared one.
inline CefRefPtr<CefRequestContext> contextFor(bool isPrivate) {
    return isPrivate ? CefRequestContext::CreateContext(CefRequestContextSettings(), nullptr) : nullptr;
}

inline bool inBackground(cef_window_open_disposition_t disposition) { return disposition == CEF_WOD_NEW_BACKGROUND_TAB; }

}  // namespace producer
