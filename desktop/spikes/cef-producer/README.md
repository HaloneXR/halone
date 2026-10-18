# Spike 2: CEF as a producer (macOS)

Phase 2 of the [desktop roadmap](../../../docs/desktop-roadmap.md). Experimental
code. It answers two questions: can CEF feed panels through spike 1's
shared-texture path while holding the frame budget, and which host stack does
that point to?

- `producer.mm` is a windowless CEF app (CEF 154, Chromium 154) with one
  off-screen browser per panel. It turns on `shared_texture_enabled` and
  `external_begin_frame_enabled`. In each `OnAcceleratedPaint`, it copies
  CEF's pooled IOSurface into the panel's own ring of IOSurfaces with a Metal
  blit. The blit has to finish before the callback returns, because CEF
  reuses the surface straight afterwards. It then sends spike 1's frame
  message.
- `consumer.m` is spike 1's consumer extended to several panels. It lays the
  panels out side by side and sends ticks, which the producer turns into
  `SendExternalBeginFrame` calls `--margin` ms before the next tick. It also
  forwards mouse, wheel and keyboard input to the panel under the pointer.
- `protocol.h` is spike 1's protocol plus panel ids, begin-frame timestamps and
  input events.
- `site/spike/` holds the test pages. `test.html` is animated (CSS, canvas and
  rAF), reports its own rAF rate and logs every input event to the console.
  `video.html` plays a 1080p60 clip and reports decoded and dropped frames.
  `prepare-site.sh` builds the site from the built system apps plus these
  pages, and encodes the VP9 and AV1 test clips with ffmpeg.

```sh
./fetch-cef.sh                                    # CEF 154 standard distribution, SHA-1 checked
cmake -G Ninja -B build -DCMAKE_BUILD_TYPE=Release && ninja -C build producer consumer
SYSTEM_DIST=<path to web/apps/system/dist> ./prepare-site.sh
python3 -m http.server 8765 --bind 127.0.0.1 --directory build/site &
./run-scenario.sh three-vp9 20 14                 # scenario, measured seconds, margin ms
build/Release/consumer --panel 1280x720@http://127.0.0.1:8765/spike/test.html --input-test 1
node cdp-eval.mjs <url part> '<js>'               # DevTools on port 9230, for poking at a panel
python3 memory.py                                 # resident memory of the CEF process tree
```

The build needs only the Command Line Tools, CMake and Ninja. The `cefclient`
sample needs full Xcode (its `.xib` needs `ibtool`); this producer doesn't. The
sandbox is off.

## Results

Apple M4, macOS 27.0, 60 Hz display, windowed. Panels are 1280x720, and the
video panel is 1920x1080. Measuring starts 4 s after the first frame and runs
for 15–20 s. "Missed" counts display ticks where an animated panel had no new
frame. CPU is the share of one core used by the whole CEF process tree
(browser, renderers and helpers) over the measured window.

| scenario | margin | active panels' fps | missed / 20 s | paint p50 / p95 | CEF CPU |
|---|---|---|---|---|---|
| animated page | 4 ms | 56.3 | 54 | 3.3 / 3.9 ms | 21% |
| animated page | 6 ms | 59.4 | 7 | 3.3 / 4.3 ms | 22% |
| animated page | 10 ms | 59.9 | 0 | 3.1 / 3.8 ms | 18% |
| 1080p60 VP9 video | 10 ms | 59.9 | 1 | 3.4 / 5.8 ms | 18% |
| 1080p60 AV1 video | 10 ms | 59.8 | 2 | 3.2 / 6.3 ms | 16% |
| launcher + animated + VP9 | 10 ms | 59.5, 59.5 | 7, 7 | 5.5 / 7.6 ms | 32% |
| launcher + animated + AV1 | 10 ms | 59.4, 59.7 | 9, 4 | 6.3 / 7.8 ms | 32% |
| launcher + animated + VP9 | 12 ms | 59.5, 59.7 | 7, 3 | 7.8 / 8.6 ms | 48% |
| launcher + animated + VP9 | 14 ms | 59.9, 59.9 | 0, 0 | 7.5 / 8.9 ms | 47% |
| launcher + settings + animated + VP9 | 10 ms | 53.9, 56.1 | 119, 75 | 6.6 / 10.4 ms | 40% |
| launcher + settings + animated + VP9 | 12 ms | 59.6, 59.6 | 4, 5 | 5.8 / 8.0 ms | 34% |
| launcher + settings + animated + VP9 | 14 ms | 59.8, 59.7 | 2, 3 | 6.4 / 8.1 ms | 32% |

Notes on the table:
- **Paint:** from `SendExternalBeginFrame` to `OnAcceleratedPaint`. Paint p50
  and p95 are for the animated page.
- **Copy and handoff:** the blit takes 0.8–1.5 ms. The handoff to the consumer
  takes 10–20 µs, as in spike 1.
- **The animated page's own rAF** reported 60.0 in every run at 10 ms margin
  and above.
- **Video:** decoded at 59.5–60 fps with 0–1 dropped frames per 2 s. Chromium
  reports both VP9 and AV1 1080p60 as `powerEfficient`, which means hardware
  decoding.
- **CPU:** varied between runs with the same setup (31–48% for three or four
  panels), without a clear cause in this spike.
- **The consumer** used 4–7% of a core.
- **Memory:** a three-panel run had 8 CEF processes (4 renderers) and about
  0.9 GB resident. That's summed RSS, so it overstates real use.
- **Static panels:** CEF only paints on damage, so a static page such as the
  launcher costs nothing between changes. The consumer just keeps showing the
  last frame.

Input, through the consumer's scripted test (`--input-test 1`): pointer moves,
left clicks that fire a button, focusing a text field and typing `hello` (with
correct `key` and `code` values), wheel scrolling of an inner scroller, and a
right click that raises `contextmenu` all reached the page.

## Findings

1. **CEF works as a producer, and the spike 1 design holds.** CEF's pooled
   IOSurface is copied on the GPU in about 1 ms and handed over in
   microseconds. Nothing is copied through the CPU.
2. **Three panels plus 1080p60 video hold 60 fps** with a 14 ms margin and no
   missed frames, using about a third to a half of one core across all of CEF.
   Video decodes in hardware.
3. **The margin has to follow the paint time, and paint time grows with the
   number of active panels.** It's 3 ms alone, 5–8 ms p50 with three or four,
   and the p95 is what matters. A fixed margin is either wasteful or too
   small, so the host should adapt each producer's margin from its measured
   p95, as spike 1 already suggested. At 60 Hz that leaves ~2–8 ms of slack
   for three or four active panels. At 90 or 120 Hz on the glasses it will be
   tighter, and that needs measuring on a faster display.
4. **The blit stalls CEF's UI thread for ~1 ms per paint per panel.** It
   scales with the number of active panels, and with four that's a large part
   of the frame. Worth moving off the blocking wait in the real engine, or at
   least measuring at higher refresh rates.
5. **Static pages can lose a repaint.** Twice, both times around a first
   launch (fresh build, fresh profile), the launcher never received the paint
   for its updated DOM. rAF was firing and the DOM was correct, but no paint
   came through, so the panel stayed on its first, blank frame. It didn't
   happen in 12 later runs. The real engine needs a safety net: call
   `Invalidate(PET_VIEW)` after load, and whenever a loaded panel has had no
   paint for a while.
6. **Stock CEF builds can't play H.264, HEVC or AAC** (`canPlayType` says
   "no"); only VP9, AV1 and Opus. Most phone and camera videos are H.264 or
   HEVC, so the desktop Video app would need a CEF build with proprietary
   codecs (which brings licensing obligations), native playback outside the
   web engine, or transcoding.
7. **Host stack: the web engine belongs in its own native process, which makes
   JCEF unnecessary.** Since spike 1 showed the handoff between processes
   costs microseconds, CEF doesn't have to live inside the host. The engine is
   this kind of native producer, about 600 lines of Objective-C++ here, and it
   speaks the same frame-callback protocol as plugins. The host only consumes
   textures and forwards input, so its language is free: Kotlin on the JVM
   keeps the shell shared with Android. JCEF wasn't evaluated, because nothing
   in this design calls for it. What remains open for the host is the
   graphics API: spike 1 pointed macOS at Metal for presentation timing, and
   LWJGL has no Metal bindings. Vulkan through MoltenVK, which LWJGL does
   bind, is a candidate worth checking in phase 4: it reports display timing,
   can import IOSurfaces, and would carry over to Windows and Linux.

## Linux

`producer_linux.cc` is the same producer for Linux; what the two share is in
`producer_common.h`. CEF's `OnAcceleratedPaint` gives a DMA-BUF that is only
valid during the callback, so the producer imports it into Vulkan (loaded at
runtime, so no Vulkan SDK is needed), waits on the GPU for CEF's writes through
the buffer's exported sync file, and copies it into the panel's own ring of
exported DMA-BUFs. Messages travel over a `SOCK_SEQPACKET` Unix socket with the
fds attached. The compositor sends `RELEASE` for each frame it is done with,
where macOS uses IOSurface use counts, and a resize starts a new generation of
surfaces so a late release cannot free a surface of the new ring.

```sh
./fetch-cef.sh                          # CEF 154 for linux64, SHA-1 checked
mise exec cmake ninja -- cmake -G Ninja -B build -DCMAKE_BUILD_TYPE=Release
mise exec cmake ninja -- ninja -C build producer
```

The compositor's own `nativeBridgeUnix` task builds its side (`desktop/native/bridge_unix.c`).
The producer passes `--password-store=basic` to keep Chromium off the desktop's keyring.

Radeon 780M (RADV, Mesa 26.2.3), KDE Wayland, 60 Hz, windowed, 10 ms margin:

| scenario | active panels' new frames / 2 s | paint p50 / p95 | copy p50 / p95 | CEF CPU |
|---|---|---|---|---|
| animated page | 120 | 2.4 / 3.9 ms | 0.4 / 0.7 ms | |
| launcher + animated + VP9 | 120, 120 | 4.1 / 5.3 ms (animated), 3.4 / 5.1 ms (video) | 0.4–0.6 / 1.3 ms | 60% |

- The video decodes at 58–61.5 fps with no dropped frames; Chromium reports
  1080p60 VP9 and AV1 as `powerEfficient`.
- The scripted input test passes: clicks, typing `hello` with correct `key`
  and `code`, and wheel scrolling. GLFW gives evdev codes on Wayland and XKB
  keycodes on X11, so the compositor sends XKB keycodes either way, and the
  producer maps them to the Windows key codes CEF dispatches on.
- 13 CEF processes, 1.4 GB summed RSS.
- The shell (`desktop/compositor/run-shell.sh`) runs too: panels opened and
  closed at runtime, the system apps served from `https://halone.local/`, and
  the bridge reaching 127.0.0.1 from that origin only.
- On quit the producer closes every browser first: CEF's shutdown never
  returns while one is open, and the compositor would wait on it forever.
- Chromium re-seeds the stack guard in forked children, so the producer's
  `main` is built without a stack protector; otherwise each child aborts as
  `main` returns.

## Not covered

- Refresh rates above 60 Hz, and the glasses' output.
- Popups (`PET_POPUP`, for `<select>` dropdowns), IME composition, drag and
  drop, cursor shapes, and resizing a panel.
- Sandboxing the CEF processes.
- The Halone bridge. The system apps run in their no-host state.
- Restarting a crashed producer, and a renderer crash inside one.
