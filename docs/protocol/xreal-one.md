# XREAL One series protocol

The One (`3318:0438`) and One Pro (`3318:0436`) do not stream sensors over HID
like the Air series. They bring up a USB network link (CDC NCM, and on the 1S
also ECM) and serve TCP on it. Nobody on this project has run this against
hardware yet: everything below comes from the sources at the end, plus one real
One Pro capture (`core-glasses/src/test/resources/xrealone/`).

## Link

| | NCM | ECM |
|---|---|---|
| Glasses | `169.254.2.1` | `169.254.1.1` |
| Host (DHCP from the glasses) | `169.254.2.x/24` | `169.254.1.x/24` |

On Android the link shows up as a `Network` with a link-local address, and
sockets must come from that network's `socketFactory`. No USB permission is
needed — **when the phone brings the link up at all, which it may not.** On a
Galaxy S25 Ultra (SM-S938N, Android 17) two test-kit runs saw no glasses
interface or `169.254.x.x` address at any point, although the glasses expose
both NCM (USB interfaces 1–2) and ECM (3–4). The interfaces Android lists to
apps only include those with an address, so whether the kernel created a
netdev that Android then ignored is unknown.

The glasses' **TCP/IP** setting must be on, or they expose no network function
over USB at all: a tester's One Pro enumerated with 7 interfaces (HID, audio,
buttons) instead of 13, with no NCM or ECM. XREAL documents the setting
([Developer page](https://tutorials.xreal.com/docs/glasses/one-series/osd/tcp-ip/)):
double-click **X** → **Other** → press **−** until **Serial Number** → hold **X**
for 5 s → **TCP/IP**. XREAL advises turning it off when a phone or TV box
mistakes the glasses for a network device, so owners may have done exactly that.

Underneath, the USB functions are a 32-bit word on the glasses, two bits per
function (NCM 0–1, ECM 2–3, audio 4–5, HID 6–7, MTP 8–9, mass storage 10–11,
cameras 12–15, enable 16–17). It's read with HID command `0xD2` and set with
`0xD3` on interface 0; when setting, 0 leaves a field alone, 1 turns it on, 2
off. Frames are 1024 bytes: `0xFD`, CRC-32 (standard), u16 length, u32 request
id, u32 timestamp, the 2-byte command, five zero bytes, payload; replies add a
status byte (xr-tools, `proto/usb`). The test kit reads this word when the menu
toggle doesn't help, and with the tester's consent writes `0x00010005` (NCM
and ECM on, nothing else). `XrealOneHid` refuses any other write. Untested on
hardware so far.

The glasses must be in **Follow** mode with **Stabilizer off**, set from their
own menu. The host can neither read nor change either. With Stabilizer on, one
project reports port 52998 staying closed, and another that the glasses re-centre
the image themselves on top of whatever the host renders.

When the phone doesn't bring the link up, Halone runs it in-process instead
(`transport/usbnet`). It claims the ECM function (control interface 3, data
interface 4 alt 1) through the USB host API, and moves one Ethernet frame per
bulk transfer. On top of that it runs its own DHCP client (falling back to
`169.254.1.10`), ARP, IPv4, and a minimal TCP client. This path needs USB
permission. ECM rather than NCM because ECM frames need no NTB parsing.

Ports: `52998` reports, `52999` control, `52996` vsync timestamps (format
unknown), `52997` camera.

## Framing

Every message on both ports:

| Offset | Size | |
|---|---|---|
| 0 | 2 | message id, big-endian |
| 2 | 4 | payload length, big-endian |
| 6 | n | payload |

## Reports (52998)

They stream as soon as the port is open. Message id `0x2836`, payload 128 bytes,
little-endian:

| Payload offset | Type | |
|---|---|---|
| `0x00` | u64 | device id |
| `0x08` | u64 | device timestamp, ns |
| `0x18` | u32 | report type: `0x0B` IMU, `0x04` magnetometer |
| `0x1C` | 3×f32 | gyro, rad/s |
| `0x28` | 3×f32 | accel, m/s² |
| `0x34` | 3×f32 | magnetometer, uncalibrated |
| `0x40` | f32 | temperature, °C |
| `0x45` | u24 | frame counter, per report type |

The fields that don't belong to a report's type are NaN. In the capture, IMU
reports arrive at 1000 Hz (Δt 999.9 µs ±18 µs), magnetometer reports at
400 Hz, and both counters have no gaps.

**Axes:** x right, y down, z forward, so the canonical frame is `(x, −y, −z)`.
Measured on a 1S, and confirmed on one One Pro from test-kit motions: the
pitch, yaw and roll pairs agree to within 12°, and worn gravity reads 3–9° from
up. The IMU is pitched about 31.5° against the head, against 35.7° in the
factory display calibration. Two sources disagree with this frame (xr-tools
documents +X left/+Y up/+Z back, and one Rust driver remaps to `(−x, −z, −y)`);
the measurement settles it for this unit.

The 1S also found the factory `gyro_bias` doesn't match the live offset, and
the magnetometer unusable without a calibration the glasses don't store. On a
still One Pro the magnetometer reads a steady ~40 (±1%), the size of Earth's
field in µT, at a believable dip angle against gravity, which suggests it
shares the IMU's axes. Halone's heading correction needs no calibration, only
a field that stays put, so the driver hands each magnetometer report to the
filter with the IMU sample after it, rotated by the factory `gyro_q_mag`.
Whether it holds the heading while worn is not yet confirmed.

## Control (52999)

Requests and replies put a u32 big-endian transaction id at the start of the
payload. Requests set its top bit, and replies echo it with the top bit clear.
Unsolicited events have no transaction id. The body is protobuf:

- getter: `18 00`
- numeric setter: `1a <len> 08 <varint>`
- reply: `22 <len> {1: status, 2: value}`. A missing status means success and a
  missing value means 0, so `22 00` is success.

| Id | | Notes |
|---|---|---|
| `0x271F` | get config | about 220 KB of JSON (see below) |
| `0x2729` | get id | string |
| `0x271D` | get firmware version | string |
| `0x271B` / `0x271C` | get / set brightness | set takes 0–9 per xr-tools; get never tried |
| `0x2821` / `0x2822` | get / set input mode | 0 = 2D, 1 = full SBS; verified on a 1S |
| `0x272E` | key event | 64 bytes: u32 key, u32 state (1 down, 2 up) |

Published examples:

```
get config  27 1f 00 00 00 06 80 00 00 01 18 00
set SBS     28 22 00 00 00 08 80 00 00 02 1a 02 08 01   → 22 00
get mode    → 22 02 10 01   (SBS)
```

Switching input mode re-plugs the display for about 1.8 s. TCP stays up.

Refresh rate is **not** settable: `0x275E`/`0x275F` (display configuration)
returned an unknown value and `status 10001` on one-xr's One Pro.

**Only the ids above are ever sent** (`XrealOneControl.ALLOWED_REQUESTS`). The
same firmware exposes USB-function and network toggles that can leave the
glasses unreachable. The HID interface carries firmware update and recovery
commands, and nothing in Halone writes to it.

## Config

Same schema as the Air series blob, so `XrealCalibrationParser` reads it as-is:
`FSN`, `IMU.device_1`, `display.k_*_display`, `target_p/q_*_display` (Hamilton,
in the IMU frame), `display_distortion` (61×35 on the One Pro sample, 61×39 on
the 1S), `glasses_version` 7–8.

The One Pro sample has the displays rotated about 35° about x relative to the
IMU, where the Air's are within a degree. `DeviceCalibration.headFromImu`
carries that into tracking and rendering. The bias vectors are read in the
report frame. Unlike the Air, there's no evidence of a separate factory frame.

When the config can't be read the driver falls back to no calibration, so
rendering falls back to the driver's `Optics`, rather than to an Air's numbers.

## Display (measured, One Pro on a Galaxy S25 Ultra)

EDID: manufacturer `MRG`, product `0x4100` (16640), name "XREAL One Pro".

| Glasses setting | Modes the phone sees |
|---|---|
| 2D | 1920×1080 at 60, 90, 120 Hz |
| Full SBS | 3840×1080 at 60 Hz |
| Half SBS | 1920×1080 at 60 Hz only |

So 2D refresh is chosen by the host's mode selection, not the glasses' menu.
Each switch re-adds the display under a new id.

## Open questions

- Does the link come up on any Android phone, and as which interface? Not on
  a Galaxy S25 Ultra, where the in-app ECM stack is needed.
- Is TCP/IP on by default, or only after a firmware update? One tester reports
  it on while their glasses enumerated without NCM/ECM on two phones, so the
  setting may not be the only gate (firmware version, or needing a replug).
- Axis signs and the mount rotation on a One Pro, worn.
- EDID name and mode list per glasses setting; SBS refresh rates.
- Brightness read and range; what events `0x2766`, `0x278A` and `0x2712` mean.
- Display latency and field of view, beyond two wearers. Blind A/B in test kit
  0.6.0 put prediction at 35 and 17.5 ms (both rejected 105) and the field of
  view at 0.83 and 0.95 of the factory calibration's; the driver uses 30 ms
  and 0.9 until more wearers weigh in. Neither could tell the factory mount
  ±5° apart.

## Sources

| | License | Used for |
|---|---|---|
| [Skarian/one-xr](https://github.com/Skarian/one-xr) | MIT | Android link selection, control framing, the capture slice |
| [SamiMitwalli/One-Pro-IMU-Retriever-Demo](https://github.com/SamiMitwalli/One-Pro-IMU-Retriever-Demo) | MIT | report stream |
| [rohitsangwan01/xreal_one_driver](https://github.com/rohitsangwan01/xreal_one_driver) | MIT | report offsets |
| [0xcaff/xr-tools](https://github.com/0xcaff/xr-tools) | none | facts only: report layout, ids, config schema |
| [kevindurb/xreal-linux](https://github.com/kevindurb/xreal-linux) | none | facts only: 1S measurements, byte examples |
| [wheaney/XRLinuxDriver](https://github.com/wheaney/XRLinuxDriver) | GPL-3.0 | USB ids only |

No code was copied from the unlicensed or GPL sources.
