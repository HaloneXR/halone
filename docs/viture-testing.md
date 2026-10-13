# Luma Pro live testing

The initial driver recognizes `35ca:1121` and `35ca:1141`. The quaternion
parser and command bytes are checked against the supplied SDK. USB interface
numbers, endpoint-zero writes, tracking direction, optical calibration and
actual stereo output are not verified on glasses yet.

## Phone setup

Enable wireless debugging on the Pixel Fold and pair from this checkout:

```sh
mise exec -- adb pair <phone-ip>:<pairing-port>
mise exec -- adb connect <phone-ip>:<debugging-port>
mise exec -- adb devices -l
mise run android:build
mise exec -- adb -s <phone-serial> install -r android/app/build/outputs/apk/debug/app-debug.apk
```

The pairing and debugging ports are different. Keep the phone and Mac on the
same network; the glasses occupy USB-C. Stop SpaceWalker or any other app using
the glasses before opening Halone.

## Start with descriptors

Plug in the Luma Pro, open Halone and accept its USB permission prompt. Capture:

```sh
mise exec -- adb -s <phone-serial> shell am start -n dev.halone.app/.MainActivity -e action "usb,displays"
mise exec -- adb -s <phone-serial> logcat -d -s Halone:I
```

Confirm the control device's VID/PID and `ifaceId` values before trying modes.
The current driver requests IMU interface 0 and MCU interface 1, from Gen1
reference implementations. A descriptor mismatch needs a driver correction;
it does not justify sending commands to arbitrary interfaces.

## Tracking

```sh
mise exec -- adb -s <phone-serial> shell am start -n dev.halone.app/.MainActivity -e action "connect,imu"
```

Expect roughly 120 samples/second and a stable heading while still. Face
forward and recenter, then turn left, look up, and tilt toward the right
shoulder one at a time. Retain the logs and describe which direction the world
moves. Check stationary drift for a minute and repeat after disconnect/reconnect.
The existing `axes` action reads raw gyro/accel; it cannot validate this fused
pose stream. Use `imu`'s yaw/pitch/roll and the visible environment instead.

Tracking uses host arrival timestamps. No measured angular velocity,
device-clock synchronization or prediction is supplied. Tap gestures that
depend on raw acceleration are unavailable on this driver.

## Stereo and recovery

```sh
mise exec -- adb -s <phone-serial> shell am start -n dev.halone.app/.MainActivity -e action "readmode,sbs,displays"
mise exec -- adb -s <phone-serial> shell am start -n dev.halone.app/.MainActivity -e action pattern
```

The preferred stereo mode is 3840×1200 at 60 Hz. Confirm each eye sees its own
L/R card, with no double image or crop. Record Android's mode and whether its
display ID changes. If this phone cannot negotiate 1200p SBS, retain the
failure log before trying the driver's 1080p SBS mode.

Use `dismiss,mirror` to restore mono, then start the environment from the home
screen. Check recentering, pointer input, stop/restart and unplug/replug. Watch
off-axis panels for apparent rotation: the advertised 52° FoV is a starting
estimate, not measured rendering calibration.

Capture the external framebuffer using the SurfaceFlinger display ID from
`dumpsys SurfaceFlinger --display-id` (different from Android's display ID).
A framebuffer screenshot proves output resolution/content; the worn L/R check
is what proves which image reaches each eye. Add these results to the PR before
marking a mode `verified = true`.

## Offline checks

```sh
mise run glasses:test
mise run android:test
mise run android:build
mise exec -- node tools/viture-sdk-probe.cjs VITURE_XR_Glasses_SDK_for_Web.zip
```

The optional SDK probe needs the local archive. Normal builds and tests do not.
Its transport is simulated; its output does not prove a device connection.
