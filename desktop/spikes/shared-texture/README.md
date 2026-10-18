# Spike 1: shared textures across processes (macOS)

Phase 1 of the [desktop roadmap](../../../docs/desktop-roadmap.md). Throwaway
code: it answers whether "every panel is a texture produced in another process"
holds on macOS, and what it costs.

- `producer` is a headless process. It renders an animated test pattern with
  Metal into a ring of IOSurfaces: a moving bar, plus the frame number in
  binary along the top-left.
- `consumer` is a window. It binds those IOSurfaces as OpenGL textures with
  `CGLTexImageIOSurface2D` (OpenGL, because the compositor is GL) and draws the
  newest one on every display-link tick.
- The two processes are unrelated except that the consumer starts the
  producer. It finds the consumer through a Mach bootstrap name the consumer
  checks in; no launchd plist is needed. The IOSurfaces cross as Mach port
  rights, once, at start-up. After that each frame is a 40-byte Mach message.
- A surface the consumer shows carries an IOSurface use count, and the producer
  never renders into a surface that is in use or was the last one sent.

```sh
make
build/consumer [--size 1920x1080] [--surfaces 4] [--mode paced|free] [--margin 4] [--rate 60] [--seconds 10]
SPIKE_TRACE=1 build/consumer --seconds 2   # per-frame timestamps from both processes
```

`q` quits and prints the summary.

## Modes

- **free:** the producer renders on its own timer at `--rate`, like a producer
  that knows nothing about the display.
- **paced** (default): on each tick, the consumer sends the producer the time it
  expects its next tick. The producer starts rendering `--margin` ms before that
  time. This is the shape a frame-callback protocol for plugins would take.

## Results

Apple M4, macOS 27.0, 60 Hz display, windowed, 10–15 s per run, two runs each.

| | render | ipc | total p50 (p95) | repeats / skips |
|---|---|---|---|---|
| free | 1.1–1.4 ms | 0.02 ms | 6.3 or 17.2–17.6 ms, depending on phase | 0–3 per run |
| paced, margin 0 | 1.4 ms | 0.02 ms | 18.4 (18.5) ms | 0 |
| paced, margin 2 | 1.3 ms | 0.02 ms | 3.8 (3.9) ms | 0–1 |
| paced, margin 3 | 1.3 ms | 0.02 ms | 4.9 (4.9) ms | 0 |
| paced, margin 4 | 1.3 ms | 0.02 ms | 5.9 (6.0) ms | 0 |
| paced, margin 4, 3840x1080 | 1.7 ms | 0.02 ms | 6.0 (6.1) ms | 0–3 |
| paced, margin 4, 3 surfaces | 1.3 ms | 0.02 ms | 6.1 (6.1) ms | 0 |

What the columns mean:
- **render:** from the producer starting a frame until Metal reports the GPU
  work complete. Of that, 0.4 ms at 1080p is actual GPU time.
- **ipc:** from GPU completion until the consumer's receive thread has the
  message.
- **total:** from the producer starting a frame until the commit deadline of
  the display tick that shows it.

Cost by surface size, paced with a 4 ms margin:

| size | producer GPU | producer CPU | consumer CPU |
|---|---|---|---|
| 1280x720 | 0.15 ms/frame | ~3% of a core | 9.2% of a core |
| 1920x1080 | 0.38 ms/frame | ~3% | 9.2% |
| 3840x1080 | 0.75 ms/frame | ~3% | 9.2% |
| 3840x2160 | 1.31 ms/frame | ~3% | 9.2% |

When the producer is killed, the consumer notices through a process-exit
source, keeps the last frame on screen and carries on.

## Findings

1. **The mechanism works, and the handoff costs nothing measurable.** Frames
   cross processes in about 20 µs.
2. **Nothing is copied through the CPU.** Consumer CPU stays the same from 720p
   to 4K; only the producer's GPU time grows with pixel count.
3. **Pacing matters more than the transport.** A producer on its own clock
   lands at a random phase: 6 ms in one run, 17 ms in the next, with occasional
   repeats as the clocks drift. A producer told when the next tick is, and
   starting a few milliseconds before it, gets about margin + 2 ms of latency
   and no repeats. A 3 ms margin covers this producer; real producers need a
   margin that fits their render time, or should adapt it from measured render
   time. The plugin protocol in phase 6 should be built around frame callbacks
   with a deadline, not around producers that push frames whenever they like.
4. **On macOS 27, an NSView display link does not fire at vsync.** It fires
   about 0.5–2 ms before the frame's commit deadline (`targetTimestamp`),
   apparently timed from how long the app's ticks take. `timestamp` is the
   previous vsync. Anything that schedules work from the display link has to
   use `targetTimestamp` and `duration`, not assume the callback is at vsync.
5. **The windowed OpenGL path gives no presentation feedback.** "Commit
   deadline" is as far as this spike can see. WindowServer composites after
   that, and how long scanout takes beyond it isn't measured. The compositor's
   `PresentTiming` and prediction depend on knowing when frames actually reach
   the display. That points the macOS compositor at Metal (`CAMetalLayer`
   drawables report `presentedTime`), or ANGLE on Metal, rather than OpenGL.
   It's a phase 4 question, but it should inform the spike 2 host decision.

## Not covered

- Several producers at once, and a real producer (CEF). That's spike 2.
- Fullscreen on the glasses' output, and motion-to-photon on the glasses.
  That's phase 4.
- Restarting a producer after it dies, and handing surfaces back after a
  resize. The protocol has room for both.
- Higher refresh rates. Only a 60 Hz display was available for these runs.
