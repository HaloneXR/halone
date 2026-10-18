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

dependencies {
    implementation(project(":core-glasses"))
    implementation(libs.hid4java)
    // hid4java asks for jna:[5.8.0,), which would float to whatever is newest.
    implementation(libs.jna) { version { strictly(libs.versions.jna.get()) } }
    runtimeOnly(libs.json)
}

application {
    mainClass = "dev.halone.desktop.glasses.GlassesProbeKt"
    applicationName = "glasses-probe"
}
