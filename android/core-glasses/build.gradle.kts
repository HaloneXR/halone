import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
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
    api(project(":core-scene"))
    // Android ships org.json; desktop hosts add their own at runtime.
    compileOnly(libs.json)
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
