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

// dev.halone:plugin-sdk, built from the sdk submodule: the shell is the other side of its protocol.
includeBuild("sdk/android")

// Layers, bottom up. No module depends on one above it.
listOf(
    "core-scene",
    "core-glasses",
    "core-glasses-usb",
    "core-display",
    "core-compositor",
    "engine-webview",
    "engine-plugin",
    "core-shell",
    "app",
).forEach { name ->
    include(":$name")
    project(":$name").projectDir = file("android/$name")
}

include(":desktop-glasses")
project(":desktop-glasses").projectDir = file("desktop/glasses")
