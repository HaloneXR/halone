# Adding support for new glasses

This project is meant to outlive the hardware we happen to own. If you have a
pair of XR glasses that isn't supported, this is the path — and you can get most
of the way with unit tests before you ever plug anything in.

You need to write **one class** and add **one line** to a list.

## What you're implementing

`GlassesDriver` has two jobs: recognise a device, and build a `GlassesDevice`
over transports it is handed.

```kotlin
class MyGlassesDriver : GlassesDriver {
    override val id = "myvendor"
    override val supportedDevices = "MyVendor One, MyVendor Pro"

    override fun probe(identity: UsbIdentity): DriverMatch? { ... }
    override fun connect(match: DriverMatch, transports: HidTransportProvider): Result<GlassesDevice> { ... }
}
```

A driver **never touches Android USB APIs**. It gets `HidTransport` objects —
`write(bytes)` and `read(into)` — and nothing else. That is what lets your
driver be tested against a recorded capture on a laptop, and it keeps
permission handling in one place.

Register it in `GlassesRegistry.default()`:

```kotlin
fun default(): GlassesRegistry = GlassesRegistry(
    listOf(
        XrealDriver(),
        MyGlassesDriver(),   // <- your line
    )
)
```

Registration is an explicit list rather than reflection or `ServiceLoader`: it
keeps drivers safe from R8, makes the supported-hardware set greppable, and
makes your contribution a one-line diff.

## Step 1: see what the hardware exposes

Build and install the app with the glasses attached, then:

```sh
adb shell am start -n dev.halone.app/.MainActivity -e action usb
adb logcat -s Halone:I
```

You'll get a full descriptor dump — interfaces, classes, endpoint addresses and
directions. **Paste this into your issue or PR.** It tells us which interface
carries sensors and whether the device is HID at all.

Interface numbers in the dump are `bInterfaceNumber` (`ifaceId=`), which is what
you put in `TransportRequest` — *not* the enumeration index.

## Step 2: capture real packets

Reading the stream and hexdumping it is the single highest-value thing you can
do. Every protocol mistake we made on XREAL was the kind that produced
plausible-looking wrong answers from synthetic fixtures, and only real bytes
caught them. Two examples worth internalising:

- The MCU's *reply* payloads sit one byte later than its *command* payloads,
  behind a status byte. Reading at the command offset returns the status, which
  is always `0` — a value that looks like a perfectly reasonable answer.
- That same endpoint carries unsolicited heartbeat and button packets. A bare
  read after a command usually returns one of those instead of your reply.

Save your captures as a `*Captures.kt` object of hex strings, the way
`XrealCaptures` does, and build your tests on those.

## Step 3: report in the canonical frame

Tracking samples must be remapped into the frame documented on
`AxisConvention`:

- **+X** right, **+Y** up, **+Z** out of the display toward the wearer
- right-handed, so positive rotation about +Y turns the head left
- gyro in rad/s, accel in m/s²

Vendors all pick something different. Declare the permutation via
`AxisConvention` and apply it in the driver, so nothing above the driver has to
care.

**Do not assume identity.** Resting the glasses on a desk is not a known enough
pose to pin down signs — our XREAL numbers at rest show gravity on +Z simply
because the glasses were lying face-up. Run the guided walkthrough:

```sh
adb shell am start -n dev.halone.app/.MainActivity -e action axes
```

It talks you through four known poses and prints the raw gyro and accel for
each, which is enough to fix every axis and sign.

## Step 4: raw IMU or on-device fusion?

Pick the `TrackingSample` variant that matches your hardware:

- `TrackingSample.Inertial` — the device streams gyro/accel. Emit these and the
  shared `HeadTracker` handles bias estimation, gravity correction, recentring
  and prediction for you. Set `providesOrientation = false`.
- `TrackingSample.Oriented` — the device fuses on-board and hands over a
  quaternion (Rokid and VITURE do this). Emit these and set
  `providesOrientation = true`. `HeadTracker` passes them through, still
  applying mount correction, recentring and prediction. Include
  `angularVelocity` if the device reports it, or prediction is unavailable.

Don't fake an IMU stream to satisfy the other variant.

## Step 5: optional capabilities

Return `null` for anything the hardware can't do. These are nullable
sub-interfaces rather than capability booleans plus throwing methods, so callers
literally cannot forget the unsupported case.

- `display: DisplayControl?` — mono/stereo switching. Model each firmware mode
  as a `DisplayMode` with a `StereoLayout`, and keep the vendor's mode byte
  private to your driver. Set `modeChangeRecreatesDisplay` if switching makes
  the host drop and re-add the display (XREAL does).
- `brightness: BrightnessControl?`
- `displayNameHints` — strings to match against `Display.getName()`, so the
  video link can be paired with the data link. These are discovered through
  entirely separate Android APIs with no shared identifier, so hints are the
  only way to correlate them.

Mark a `DisplayMode` as `verified = true` only once you've confirmed it on real
hardware. Mode selection prefers verified modes over theoretically better ones,
because a mode the firmware rejects is worth less than a slower one that works.

## Step 6: pass the contract

Subclass `GlassesDriverContract` and give it a known identity plus transports.
With `ReplayHidTransport` over your captures, this runs with no hardware:

```kotlin
class MyGlassesDriverContractTest : GlassesDriverContract() {
    override fun driver() = MyGlassesDriver()

    override fun knownIdentity() = UsbIdentity(vendorId = 0x1234, productId = 0x5678)

    override fun transportsFor(match: DriverMatch) = object : HidTransportProvider {
        override fun open(interfaceId: Int): Result<HidTransport> =
            Result.success(ReplayHidTransport(inbound = listOf(MyCaptures.SENSOR_AT_REST)))
    }
}
```

The contract checks the things that are easy to get subtly wrong: that `probe`
is pure and rejects unrelated devices, that optics are physically plausible,
that mode tables are self-consistent and have unique keys, that closing twice
is safe. `ReplayHidTransport` also records everything written, so you can assert
your driver sends the right command bytes.

```sh
./gradlew :core-glasses:test
```

## Step 7: verify on hardware

With the [development actions](dev-actions.md):

```sh
adb shell am start -n dev.halone.app/.MainActivity -e action "connect,imu"
adb shell am start -n dev.halone.app/.MainActivity -e action drift
adb shell am start -n dev.halone.app/.MainActivity -e action "readmode,sbs,displays"
adb shell am start -n dev.halone.app/.MainActivity -e action pattern
```

Check that the sample rate roughly matches your declared `imuRateHz`, that
`drift` stays small once bias is calibrated, and — if you claim stereo — that
each eye really does see one half of the test pattern. You can confirm the
framebuffer without wearing them:

```sh
adb shell dumpsys SurfaceFlinger --display-id     # find the glasses' id
adb shell screencap -d <that id> -p /sdcard/g.png
```

Note that `screencap` wants the SurfaceFlinger id, not `Display.getDisplayId()`.

## What to include in a PR

- The driver, plus its captures and contract test
- The one-line registry change
- The `usb` descriptor dump, in the PR body
- A note on which models you physically tested, and which are inferred — mark
  the untested ones `verified = false` and say so. We would much rather ship
  honest partial support than a table that overstates what's been tried.

## If it isn't USB HID

`HidTransport` is deliberately just "bytes in, bytes out", so Bluetooth or a
vendor SDK can sit behind it. If your device needs a genuinely different shape —
control transfers, isochronous streams, an SDK that owns its own threads — open
an issue before writing much code; the transport layer is the right place to
extend, and we would rather extend it with you than have you fight it.
