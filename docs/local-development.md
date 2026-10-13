# Local development with mise

Use [halone.dev](https://halone.dev/) for the published developer documentation:
[hardware drivers](https://halone.dev/hardware/),
[development actions](https://halone.dev/hardware/dev-actions),
[web apps](https://halone.dev/web/getting-started), and
[native plugins](https://halone.dev/native/getting-started).
The hardware guide is the entry point for VITURE support; native plugins are
a separate app extension mechanism. The repository README specifies exact
shell toolchain versions.

Run commands from the repository root. `mise install` installs the pinned
Java 17, Node 24 and pnpm 10 toolchain; `mise run` supplies it to each task.
Gradle uses the checked-in wrapper rather than a separately installed Gradle.

Install Android SDK platform `platforms;android-37.1` and build tools
`build-tools;37.0.0` using your Android SDK manager. Set `sdk.dir` in the
ignored `local.properties` to your SDK directory. Keep machine paths out of
the shared mise configuration.
Gradle also installs NDK `27.1.12297006` and CMake `3.22.1` on its first build
if absent.
Set `ANDROID_HOME` in the ignored `mise.local.toml` as well: the SDK submodule
is a separate Gradle build and does not inherit the root's `local.properties`.
Add an `_.path` array containing the SDK's `platform-tools` and
`cmdline-tools/latest/bin` directories to make adb and Android tools available
through `mise exec`.

```sh
mise install
mise run setup
mise run web:check
mise run android:test
mise run android:build
mise run dev
```

The simulator is at http://127.0.0.1:5173 and system apps at
http://127.0.0.1:5174. Stop both with Ctrl-C.

If the SDK submodule's SSH URL is unavailable, use a checkout-local override:

```sh
mise exec -- git config submodule.sdk.url https://github.com/HaloneXR/halone-sdk.git
mise run setup
```

## VITURE bring-up

Start with [adding-a-device.md](adding-a-device.md) and
[dev-actions.md](dev-actions.md). `mise run glasses:test` runs the existing
driver contract and tracking tests without hardware. Hardware verification
needs a phone and glasses, with adb over WiFi while USB-C is occupied.
Use `mise exec -- ./gradlew :app:installDebug` once the phone is connected.

The Android and Web VITURE SDK archives supplied locally are reference inputs;
they have not been integrated. Confirm their APIs and redistribution terms
before choosing a transport or checking vendor binaries into the repository.
Packet captures, axis measurements and real stereo checks are still required
to claim device support.
