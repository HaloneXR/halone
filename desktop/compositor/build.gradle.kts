import java.time.LocalDate
import java.time.format.DateTimeFormatter
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

val os = System.getProperty("os.name").lowercase()
val arch = System.getProperty("os.arch")
val lwjglNatives = when {
    os.contains("mac") -> if (arch == "aarch64") "natives-macos-arm64" else "natives-macos"
    os.contains("win") -> if (arch == "aarch64") "natives-windows-arm64" else "natives-windows"
    else -> if (arch == "aarch64") "natives-linux-arm64" else "natives-linux"
}

val nativeBridge = tasks.register<Exec>("nativeBridge") {
    val source = rootProject.layout.projectDirectory.dir("desktop/native")
    val output = layout.buildDirectory.file("native/darwin-aarch64/libhalone_bridge.dylib")
    onlyIf { System.getProperty("os.name").lowercase().contains("mac") }
    inputs.dir(source)
    outputs.file(output)
    commandLine(
        "clang", "-dynamiclib", "-O2", "-Wall", "-arch", "arm64",
        "-fobjc-arc", "-o", output.get().asFile.path, source.file("bridge.c").asFile.path, source.file("system_mac.m").asFile.path,
        source.file("display_mac.m").asFile.path, source.file("keys_mac.m").asFile.path,
        "-framework", "IOSurface", "-framework", "CoreFoundation", "-framework", "Foundation", "-framework", "IOKit",
        "-framework", "CoreGraphics", "-framework", "AppKit", "-framework", "ApplicationServices", "-framework", "Carbon", "-framework", "ImageIO", "-framework", "QuickLookThumbnailing",
        "-framework", "CoreWLAN", "-framework", "SystemConfiguration", "-framework", "UniformTypeIdentifiers",
    )
    doFirst { output.get().asFile.parentFile.mkdirs() }
}
val nativeBridgeUnix = tasks.register<Exec>("nativeBridgeUnix") {
    val source = rootProject.layout.projectDirectory.dir("desktop/native")
    val jnaArch = if (arch == "aarch64") "aarch64" else "x86-64"
    val output = layout.buildDirectory.file("native/linux-$jnaArch/libhalone_bridge.so")
    onlyIf { System.getProperty("os.name").lowercase().contains("linux") }
    inputs.dir(source)
    outputs.file(output)
    commandLine(
        "cc", "-shared", "-fPIC", "-O2", "-Wall", "-Werror",
        "-o", output.get().asFile.path, source.file("bridge_unix.c").asFile.path,
    )
    doFirst { output.get().asFile.parentFile.mkdirs() }
}
// Versioned by date as the Android app is: 2026.10.11-alpha. Pass -Phalone.date=2026.10.11 to pin one.
val haloneVersion = ((findProperty("halone.date") as String?)
    ?: LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))) + "-alpha"
val writeVersion = tasks.register("writeVersion") {
    val output = layout.buildDirectory.file("generated/version/halone-version.txt")
    val version = haloneVersion
    inputs.property("version", version)
    outputs.file(output)
    doLast { output.get().asFile.apply { parentFile.mkdirs() }.writeText(version) }
}
val helperLinux = tasks.register<Exec>("helperLinux") {
    val source = rootProject.layout.projectDirectory.file("desktop/native/helper_linux.c")
    val output = layout.buildDirectory.file("helper/halone-helper")
    onlyIf { System.getProperty("os.name").lowercase().contains("linux") }
    inputs.file(source)
    outputs.file(output)
    commandLine("cc", "-O2", "-Wall", "-Wextra", "-Werror", "-pthread", "-o", output.get().asFile.path, source.asFile.path)
    doFirst { output.get().asFile.parentFile.mkdirs() }
}
distributions.main { contents { from(helperLinux) { into("bin") } } }
sourceSets.main {
    resources.srcDir(layout.buildDirectory.dir("native"))
    resources.srcDir(layout.buildDirectory.dir("generated/version"))
}
tasks.processResources {
    dependsOn(nativeBridge, nativeBridgeUnix, writeVersion)
    from(rootProject.layout.projectDirectory.dir("android/app/src/main/assets/cursors")) { into("cursors") }
}

dependencies {
    implementation(project(":core-glasses"))
    implementation(project(":core-compositor-common"))
    implementation(project(":desktop-glasses"))
    implementation(project(":desktop-shell"))
    implementation(libs.jna) { version { strictly(libs.versions.jna.get()) } }
    implementation(libs.lwjgl)
    implementation(libs.lwjgl.glfw)
    implementation(libs.lwjgl.vulkan)
    implementation(libs.lwjgl.shaderc)
    runtimeOnly(variantOf(libs.lwjgl) { classifier(lwjglNatives) })
    runtimeOnly(variantOf(libs.lwjgl.glfw) { classifier(lwjglNatives) })
    runtimeOnly(variantOf(libs.lwjgl.shaderc) { classifier(lwjglNatives) })
    if (os.contains("mac")) runtimeOnly(variantOf(libs.lwjgl.vulkan) { classifier(lwjglNatives) })
    testImplementation(libs.junit)
}

application {
    mainClass = "dev.halone.desktop.compositor.MainKt"
    applicationName = "halone-desktop"
    if (os.contains("mac")) applicationDefaultJvmArgs = listOf("-XstartOnFirstThread")
}
