# Development actions

Development actions are for bringing up a device and checking on a running
session. They have no buttons; drive them over adb and read the results in
logcat:

```sh
adb shell am broadcast -a dev.halone.app.ACTION -p dev.halone.app -e action displays
adb shell am start -n dev.halone.app/.MainActivity -e action "connect,imu"
adb logcat -s Halone:I
```

| key        | what it does |
|------------|--------------|
| `start` / `stop` | toggle the environment (connect, stereo, shell): `start` stops one already running; `stop` only stops |
| `usb`      | dump every attached USB device with full interface/endpoint descriptors |
| `connect`  | request permission, claim the IMU + MCU interfaces, start the IMU stream |
| `imu`      | stream for 10s; reports sample rate and fused yaw/pitch/roll |
| `axes`     | guided walkthrough to pin down a device's axis convention |
| `gravity`  | which axis is gravity on right now |
| `drift`    | measure residual yaw drift over 60s after bias calibration |
| `calib`    | dump the per-unit factory calibration blob (FoV, distortion, biases) |
| `calibsource` | which calibration is in use — the unit's own, or the fallback |
| `calibcheck` | check the factory bias against this device, at rest |
| `readmode` | read the current display mode over the MCU interface |
| `sbs` / `mirror` | set the stereo (side-by-side) or mono display mode |
| `displays` | enumerate displays and every supported mode, flagging SBS candidates |
| `pattern` / `dismiss` | show or tear down a per-eye L/R test card |
| `latencyest` | software-only motion-to-photon estimate |
| `taprecord` | record taps on the glasses frame for tuning the tap detector |
| `recenter` | make the current heading forward |
| `gaze`     | report where the pointer hits |
| `frames` / `timing` / `present` / `hitches` | frame health, display timing and present timing |
| `phasesweep` | how steadily frames reach the glasses at each draw phase, a tenth of a refresh apart (40 s) |
| `panels` / `why` | every panel's slot and heading; what the arrangement is doing |
| `shellstatus` | shell windows and their bridge connections |
| `focusfield` / `focustap` | focus a text field, or tap the middle, of the focused window |
| `resize`   | grow the newest window by a quarter |
| `inputlog` | log pointer events and keys (toggle) |
| `settings` | open Settings on the phone |

Several can be chained: `-e action "usb,displays"`.
