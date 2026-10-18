pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.mozilla.org/maven2") { name = "mozilla" }
    }
}

rootProject.name = "halone"

// -Phalone.desktopOnly builds the desktop modules and the plain JVM ones they use,
// so a machine without the Android SDK can build them.
val desktopOnly = providers.gradleProperty("halone.desktopOnly").map { it != "false" }.getOrElse(false)

// dev.halone:plugin-sdk, built from the sdk submodule: the shell is the other side of its protocol.
if (!desktopOnly) includeBuild("sdk/android")

// Layers, bottom up. No module depends on one above it.
val androidModules = setOf("core-glasses-usb", "core-display", "core-compositor", "engine-webview", "engine-plugin", "app")
listOf(
    "core-scene",
    "core-glasses",
    "core-glasses-usb",
    "core-compositor-common",
    "core-shell",
    "core-display",
    "core-compositor",
    "engine-webview",
    "engine-plugin",
    "app",
).filter { !desktopOnly || it !in androidModules }.forEach { name ->
    include(":$name")
    project(":$name").projectDir = file("android/$name")
}

include(":desktop-glasses")
project(":desktop-glasses").projectDir = file("desktop/glasses")
include(":desktop-compositor")
project(":desktop-compositor").projectDir = file("desktop/compositor")
