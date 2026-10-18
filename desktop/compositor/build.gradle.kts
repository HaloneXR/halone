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

dependencies {
    implementation(project(":core-glasses"))
    implementation(project(":core-compositor-common"))
    implementation(project(":desktop-glasses"))
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
