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
- DRM (Widevine). Services that need it, such as Netflix, Disney+ and Prime
  Video, won't play. Supporting it would mean Google licensing, special signing
  of the app (VMP), and ongoing obligations that aren't worth taking on.

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
- **Done 2026-10-18 on a 60 Hz display; it holds.** Three panels plus 1080p60
  video (hardware-decoded VP9 or AV1) run at 60 fps with a 14 ms begin-frame
  margin, using a third to a half of one core across CEF, and all input types
  reach the page. The engine runs as its own native producer process, so the
  host stack question becomes "which graphics API", not "JCEF or native": JCEF
  isn't needed. Open items: an adaptive margin per producer; a repaint safety
  net for static pages; no H.264, HEVC or AAC in stock CEF builds; and refresh
  rates above 60 Hz still to measure. CEF 154 documents shared textures on
  Windows (D3D11/12 handles) and Linux (dmabuf planes) too, but only macOS was
  tried. See
  [desktop/spikes/cef-producer](../desktop/spikes/cef-producer/README.md).

### 3. Glasses on desktop, headless

`core-glasses` builds for the JVM, with a hidapi (or hid4java) transport.

- Exit: the existing replay and contract tests pass on the desktop JVM; a
  command-line tool prints the fused orientation from real glasses on macOS.
- Check: macOS lets a user process open the IMU interfaces without claiming
  them from the system.
- **Done 2026-10-18.** `core-glasses` is now a plain Kotlin/JVM module rather
  than KMP: both Android and the desktop host are JVMs, so nothing needs
  cross-compiling. The Android USB classes moved to `core-glasses-usb`. All
  183 tests pass on a plain JVM. `desktop/glasses` adds a hid4java transport
  and `glasses-probe`. On an Air 2 Pro plugged into an M4 Mac:
  - The IMU streams at 999 samples/s with no gaps.
  - The factory calibration reads from the device, and the MCU interface
    reads the display mode and brightness.
  - Converged drift at rest is 0.08°/min, against Android's ~0.2°/min.
  - macOS exposes the IMU, MCU and timing interfaces under a vendor usage
    page, with no permission prompt, no root and no driver to detach.

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
- **In progress (2026-10-18), on Vulkan rather than GL.** `desktop/compositor`
  uses LWJGL with MoltenVK on macOS (Vulkan 1.3 on an M4, with
  `VK_EXT_metal_objects`, `VK_GOOGLE_display_timing` and present id/wait
  available).
  - The golden fixtures turned out to be numeric scene traces, not images.
    They pass wherever `core-scene` runs, so they don't test the renderer.
  - The pure math moved to a new plain-JVM module, `core-compositor-common`
    (distortion mesh, reprojection, present timing). The renderer mirrors
    `StereoCompositor`'s matrices and draw order for now. Pulling that frame
    logic out of the GL code, so both backends share it, is a later change
    for `main`.
  - Done:
    - Windowed mono with mouse-look, at 120 fps on a 120 Hz display.
    - Side-by-side stereo on an Air 2 Pro, steady at 60 Hz: calibrated
      per-eye frustums and poses, the factory distortion grid at 1.8%
      overfill, `HeadTracker` for the pose, and SBS switched over HID with
      mono restored on exit. Worn: fusion, scale, straight edges,
      world-locking and level all looked right.
    - Fixed: GLFW minimised the fullscreen window whenever the Mac's focus
      moved, which took the scene off the glasses.
    - Smoothing, measured with `VK_GOOGLE_display_timing` and the glasses'
      own display clock:
      - Presents land exactly on the glasses' refresh boundaries, on the
        same clock as `System.nanoTime`.
      - Even with an empty queue, macOS shows a frame two refreshes after
        submission (~32 ms).
      - The first wear's pipeline put ~66 ms between the head pose and the
        photon. Three things brought it to ~31 ms:
        - a 2-image swapchain paced on present-wait;
        - taking MoltenVK's ~12 ms drawable wait in a separate submission
          before sampling the pose;
        - aiming at the photon learned from measured presents (0.00 ms aim
          error).
      - On top of that comes `StereoCompositor`'s late first-row and
        last-row warp.
    - Blind A/B, worn: the new pipeline was smoother "by a lot"; the old one
      "feels like jelly, especially after fast movements".
    - Blind scan-direction sweep: +1 (top row first, a full refresh) beat 0
      and −1, which confirms the default Android uses on the same panel. A
      finer sweep couldn't separate 0.75 from 1.0, and 1.25 and 1.5 were
      worse; +1 is kept. The 1080 visible lines out of ~1125 put the true
      value at about 0.96.
  - Left:
    - A faint shear remains during turns, probably prediction error rather
      than the scan span. Worth an A/B on the prediction itself.
    - Below two refreshes of latency would need macOS to skip
      compositing, for example through direct-to-display in native
      fullscreen. Not explored.
    - Panels from producers (IOSurfaces through `VK_EXT_metal_objects`).
    - Chrome: frames, title bars, cursor and notices. Java2D can replace the
      Android Canvas painters.

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

### 7. Native video panel

Halone's own apps play local media through the OS instead of the web engine. A
built-in producer decodes with the platform's media framework into a panel's
shared textures, over the phase 6 contract.

- macOS: AVFoundation. H.264, HEVC and AAC decode on Apple's hardware, and the
  codec licensing comes with the OS.
- Windows: Media Foundation. Linux: GStreamer or ffmpeg, with whatever codecs
  the distribution provides.
- The Video, Files and Images apps ask the bridge to open a native video panel
  for a file. The controls stay in the web app and act through the bridge
  (play, pause, seek, position, tracks).
- Why: it covers phone and camera recordings, which stock CEF can't play (see
  [Known limitations](#known-limitations)). It's also the first real producer
  for the phase 6 contract.
- Exit: an H.264 and an HEVC recording from a phone play at their own frame
  rate with hardware decoding and audio, seek, and are controlled from the
  Video app.

### 8. Windows and Linux

- Windows: DXGI shared handles, HID through hidapi, output placement.
- Linux: DMA-BUF, hidraw permissions (a udev rule), output placement, which is
  the hard part on Wayland.

### 9. Platform plugins

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

1. `core-glasses` becomes a plain Kotlin/JVM module; `UsbHidTransport` and
   `DeviceDescriptorDump` move to `core-glasses-usb` (phase 3). Done on this
   branch as its own commit, ready to move to `main`.
2. `PanelEngine` returns a texture source instead of a `SurfaceTexture`
   (phase 4).
3. `Bundle` and `WebMessagePortChannel` leave `core-shell`'s public surface
   (phase 5).
4. The plugin contract is described once and the AIDL side follows it
   (phase 6).

Everything else lives in new desktop modules on this branch. Rebase on `main`
regularly so the branch stays close.

## Known limitations

- **Websites can't use H.264, HEVC or AAC.** Stock CEF builds play VP9, AV1,
  Opus and MP3, but not these (found in spike 2). Large streaming sites ask the
  browser what it supports and fall back to VP9 or AV1. But sites that serve a
  single MP4 file, offer AAC-only audio, or stream only H.264 won't play.
  - ffmpeg isn't a fallback. Chromium already bundles its own ffmpeg and fixes
    the accepted formats at build time, and transcoding on the fly breaks
    seeking and costs a real-time encode.
  - The fix is a CEF build with proprietary codecs: Chromium built from source
    for every update, with the codec licensing checked first.
  - Until then it stays a limitation. Local files are covered by the native
    video panel (phase 7).
  - Android is not affected: its system WebView and GeckoView decode these
    formats through the platform.

## Open questions

- Packaging per OS, and whether to sign and notarize builds during the
  experiment.
- How to present installing a native plugin, given it is trusted code.
- Whether the Settings and Developer apps need desktop-specific sections, or
  the bridge hides the differences.
- Which pointer model replaces the phone's pointer pad: mouse only, or also a
  phone paired as a remote.
