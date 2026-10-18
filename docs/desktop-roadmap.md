# Desktop roadmap

**Status:** experimental, on the `desktop` branch · **Date:** 2026-10-13

Halone running natively on Windows, macOS and Linux, with the glasses plugged
into the computer. This is an experiment: nothing here is committed to, and the
Android app stays the product.

## Why

- **Ecosystem.** Halone web apps already run anywhere a browser does. A desktop
  shell gives them more places to run without changing them.
- **Platform plugins.** Native plugins that only make sense on a desktop: a
  Wayland server on Linux, virtual desktops on Windows and macOS.
- **A truer simulator.** The real shell, scene and bridge running in a window.
  This removes the gaps between the simulator and desktop Halone, not the ones
  with Android (WebView/GeckoView vs CEF, no APK plugins), so it complements the
  browser simulator rather than replacing it. The browser simulator stays as
  the zero-install option for app developers.

## Non-goals

- Running Android APK plugins or the Android Apps plugin on desktop.
- Changing how the Android app works to suit the desktop. Shared code moves
  only when the move is a no-op for Android.
- A store, an installer or auto-update before the experiment proves itself.

## Starting point

| Module | Android coupling | On desktop |
|---|---|---|
| `core-scene` | none, already KMP (JVM + JS) | as is |
| `core-glasses` | `UsbHidTransport`, `DeviceDescriptorDump` | a hidapi transport; drivers, fusion, calibration and their replay tests as is |
| `core-shell` | `WebMessagePortChannel`, `Bundle` in `Shell.kt` | as is once those are behind interfaces |
| `core-compositor` | GLES, EGL, `SurfaceTexture`, OES textures | GL layer ported; scene logic as is |
| `core-display` | `DisplayManager`, `Presentation` | rewritten: the glasses are an ordinary monitor |
| `engine-webview`, GeckoView | entirely | replaced by a CEF engine |
| `engine-plugin` | binder, `PackageManager` | same contract, new transport |
| `app` | entirely | a new desktop host |
| `web/`, `sdk/` | none | as is |

## Architecture direction

**Every panel is a texture produced in another process.** The compositor shows
GPU textures shared across processes, one kind per OS: IOSurface on macOS,
DXGI shared handles on Windows, DMA-BUF on Linux. The web engine (CEF with
shared-texture offscreen rendering) and native plugins are both producers. This
is the desktop counterpart of the `Surface` a plugin draws into on Android, and
it is the one hard problem: solving it answers both the web-engine and the
plugin question.

**The host is JVM + LWJGL + CEF**, tentatively, so the shell and compositor
stay one Kotlin codebase with Android. Spike 2 decides it; if CEF's shared
textures cannot be reached from the JVM at a reasonable cost, the alternative
is a native host (C++ or Rust) around CEF that embeds the shared Kotlin modules
or reimplements the compositor.

**Native plugins are trusted code.** On Android a plugin runs under its own UID
with its own permissions. On desktop it runs as the user and can do anything
the user can. Web apps keep their permission model everywhere, so the line is:
web apps are sandboxed, native plugins are trusted and installed deliberately.

## Phases

Each phase ends with something that can be run and a decision about whether to
continue. Phases 1 and 2 are spikes: throwaway code, measured results.

### 1. Spike: shared textures across processes (macOS)

A separate process renders an animated test pattern into an IOSurface and
passes it to a GL window in another process, which draws it as a quad.

- Exit: the consumer shows the producer's frames at display rate with no CPU
  copy. Measure the latency from producer draw to consumer present.
- Decides: whether the "every panel is a producer" direction holds.
- **Done 2026-10-18; the direction holds.** Handoff ~20 µs, no CPU copy, and
  with vsync pacing about margin + 2 ms of latency and no repeats. Two
  consequences: producers should be paced by frame callbacks with a deadline,
  and windowed OpenGL on macOS reports no presentation times, which points the
  macOS compositor at Metal. See
  [desktop/spikes/shared-texture](../desktop/spikes/shared-texture/README.md).

### 2. Spike: CEF as a producer

CEF renders a Halone system app offscreen with shared textures enabled
(`OnAcceleratedPaint`), and the window from spike 1 shows it.

- Exit: two or three panels plus a 1080p video hold the frame budget at the
  glasses' refresh rate. Input (click, scroll, keyboard) reaches the page.
- Check: whether JCEF exposes accelerated paint or needs a JNI shim; which
  platforms the CEF version supports shared textures on.
- Decides: the host stack (JVM + JCEF, or native).

### 3. Glasses on desktop, headless

`core-glasses` builds for the JVM, with a hidapi (or hid4java) transport.

- Exit: the existing replay and contract tests pass on the desktop JVM; a
  command-line tool prints the fused orientation from real glasses on macOS.
- Check: macOS lets a user process open the IMU interfaces without claiming
  them from the system.

### 4. Desktop compositor

The GL layer of `core-compositor` runs on LWJGL, behind a texture-source
abstraction that `SurfaceTexture` also implements on Android.

- Windowed mode first: mono, mouse-look, no glasses. This is the "real shell in
  a window" target for development.
- Then the glasses: switch them to SBS over HID, put a fullscreen window on
  their output, distortion and prediction as on Android.
- Retune `PresentTiming` and prediction for each OS's vsync and frame timing.
- Exit: the scene from `fixtures/scene/golden` renders identically; panels
  follow head motion on the glasses without visible judder.

### 5. Shell and bridge

`core-shell` runs on the JVM, with desktop backends for files, sockets, media,
settings and shortcuts. The simulator's dev server is a reference for several.

- Input: mouse and keyboard directly; keep the system cursor from wandering
  onto the glasses' display while Halone owns it.
- Exit: the launcher, settings, files and a third-party app from
  halone-examples run unmodified; `halone dev` targets the desktop shell.

### 6. Plugin contract

The plugin contract (the four AIDL interfaces and `PluginContract`) becomes a
platform-neutral schema, with two transports: binder on Android and a Unix
socket or named pipe on desktop.

- Discovery: a plugins folder, one manifest per plugin (id, contract version,
  executable).
- Lifecycle: the shell starts each enabled plugin as a child process and treats
  its exit like a binder death.
- Pixels: shared-texture handles from phase 1, passed over the socket.
- Exit: a sample plugin written in a language other than Kotlin opens a panel,
  takes input and survives being killed and restarted.

### 7. Windows and Linux

- Windows: DXGI shared handles, HID through hidapi, output placement.
- Linux: DMA-BUF, hidraw permissions (a udev rule), output placement, which is
  the hard part on Wayland.

### 8. Platform plugins

Each is a project of its own, after the platform works.

- **Wayland server (Linux).** Smithay or wlroots; each toplevel is a panel, and
  clients' DMA-BUFs go to the compositor without copies.
- **Virtual desktops (Windows).** Desktop Duplication for capture, `SendInput`
  for input. Extra displays need an IddCx virtual display driver, which has to
  be signed and installed.
- **Virtual desktops (macOS).** ScreenCaptureKit for capture, `CGEvent` for
  input, both behind user permissions. Extra displays need `CGVirtualDisplay`,
  a private API that may break with OS updates and rules out the App Store.

## Changes to main

Kept to refactors that are no-ops on Android, each landed on `main` as its own
change only when a phase needs it:

1. `core-glasses` becomes KMP like `core-scene`; `UsbHidTransport` moves to the
   Android source set (phase 3).
2. `PanelEngine` returns a texture source instead of a `SurfaceTexture`
   (phase 4).
3. `Bundle` and `WebMessagePortChannel` leave `core-shell`'s public surface
   (phase 5).
4. The plugin contract is described once and the AIDL side follows it
   (phase 6).

Everything else lives in new desktop modules on this branch. Rebase on `main`
regularly so the branch stays close.

## Open questions

- Packaging per OS, and whether to sign and notarize builds during the
  experiment.
- How to present installing a native plugin, given it is trusted code.
- Whether the Settings and Developer apps need desktop-specific sections, or
  the bridge hides the differences.
- Which pointer model replaces the phone's pointer pad: mouse only, or also a
  phone paired as a remote.
