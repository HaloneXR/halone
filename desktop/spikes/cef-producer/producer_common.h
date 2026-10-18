#pragma once

#include <algorithm>
#include <sstream>
#include <string>
#include <vector>

#include "include/cef_browser.h"
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

}  // namespace producer
