# Halone

A web-based 3DoF XR environment for XREAL Air-series glasses driven from an
Android phone — a spiritual replacement for the discontinued Nebula Android
app. Native Kotlin shell (compositor, IMU driver, input, window management);
every UI surface, including the launcher and system apps, is a web app talking
to a native bridge.

**Status: alpha.** It works daily on an XREAL Air 2 Pro, and has been checked
on the original XREAL Air; expect rough edges.
Bug reports and pull requests are welcome.

## Layout

```
android/core-glasses/    USB HID · IMU parse · fusion · prediction
android/core-display/    display discovery · hotplug · mode switch · presentation
android/core-compositor/ Panel/Scene model · per-eye render · follow · layout
android/engine-webview/  VirtualDisplay + Presentation -> SurfaceTexture
android/core-shell/      window manager and Halone bridge host for the web environment
android/app/             the app: home screen, environment, pointer pad, development actions over adb
web/                     system apps, desktop simulator (see web/README.md)
sdk/                     submodule: halone-sdk (@halone/sdk, @halone/cli, dev.halone:plugin-sdk)
docs/protocol/           Reverse-engineered HID protocols
tools/                   Offline analysis scripts, and the icon and cursor fetchers (tools/icons)
docs/adding-a-device.md  How to support new hardware
docs/dev-actions.md      Development actions over adb: device bring-up and diagnostics
docs/adr/                Architecture decision records
docs/multi-panel.md      Follow, layout and collision model
```

Modules are layers: none depends on one above it. A panel owns its own pose,
heading, texture, engine and follow state, so "how many panels" is a scene
question rather than a renderer one. Following is a property of the *arrangement*
rather than of a panel — see [docs/multi-panel.md](docs/multi-panel.md), which is
worth reading before changing any of it.

## Hardware support

| Device | Tracking | Stereo | Brightness | Status |
|---|---|---|---|---|
| XREAL Air 2 Pro | ~994 Hz raw IMU, axes measured, **per-unit calibration read from the glasses** | full SBS 3840x1080 | yes | **verified on hardware** |
| XREAL Air | raw IMU, **per-unit calibration read from the glasses** | full SBS 3840x1080 | yes | **verified on hardware** |
| XREAL Air 2 | expected | expected | expected | same protocol, untested |
| XREAL Air 2 Ultra | expected | expected | expected | different interfaces, untested |
| VITURE Luma Pro | 120 Hz fused pose, SDK-checked parser | full SBS 3840x1200 at 60 Hz | 0–8 | **awaiting hardware verification**; USB IDs 35ca:1121 / 35ca:1141 |

Glasses we don't own are welcome — the driver layer is designed so support can
be written and tested against recorded packet captures with no hardware.
See [docs/adding-a-device.md](docs/adding-a-device.md). It's one class plus one
line in a list, and there's a reusable contract test suite to check it.

VITURE bring-up and evidence limits are in [docs/viture-testing.md](docs/viture-testing.md).
The driver currently covers Luma Pro; Beast, Pro 2 and Luma Ultra use different
protocols. Vendor SDK binaries are not needed to build or run Halone.

## Toolchain

For a mise-managed local setup, see [docs/local-development.md](docs/local-development.md).
Run `mise install` and `mise run setup`, then `mise run dev` for the desktop
simulator or `mise run android:build` for a debug APK. Published developer
guides are at [halone.dev](https://halone.dev/), including the
[hardware driver guide](https://halone.dev/hardware/).

- JDK 17 (`/Library/Java/JavaVirtualMachines/zulu-17.jdk`) — AGP does not support 22/24
- Android SDK 37.1, Gradle 9.5.0, AGP 9.3.1, Kotlin 2.4.10, GeckoView 154
- `android.builtInKotlin=false` and `android.newDsl=false` are deliberate and
  temporary: AGP 9's built-in Kotlin compiler cannot read the Kotlin 2.4
  metadata in the androidx artifacts GeckoView pulls in, so the standalone
  Kotlin plugin is used until it can
- adb **over WiFi**, because the USB-C port is occupied by the glasses

The SDK is the [halone-sdk](https://github.com/HaloneXR/halone-sdk)
submodule in `sdk/`. It is published as `@halone/sdk` on npm and
`dev.halone:plugin-sdk` on Maven Central, but the shell is the other side of
its protocol, so it builds against the submodule instead: a pnpm override
points `@halone/sdk` at it, and Gradle builds `dev.halone:plugin-sdk` from it
as a composite build. The examples, including
the sample plugin, are in
[halone-examples](https://github.com/HaloneXR/halone-examples).

```sh
git clone --recurse-submodules git@github.com:HaloneXR/halone.git
cd halone/web && pnpm run sdk && pnpm install && pnpm build && cd ..
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
./gradlew test                # no device needed
./gradlew :app:installDebug
```

After pulling a change to the submodule, run `git submodule update` and
`pnpm run sdk` in `web/` again.

## The app

The phone opens to the glasses' status and a button that starts the
environment on them; the gear in the corner opens Settings on the phone. While
the environment runs, the phone is the pointer pad.

For bringing up new hardware or checking on a running session, there are
[development actions](docs/dev-actions.md) driven over adb.

## License

Halone is released under the [MIT License](LICENSE). Bundled third-party work
keeps its own license: the Papirus icons (GPL-3.0), the Material Bibata cursors
(MIT, after Bibata, GPL-3.0), Material Symbols (Apache-2.0), GeckoView
(MPL-2.0) and the rest listed in Settings → About, whose license texts ship in
`web/apps/system/public/licenses/`.
