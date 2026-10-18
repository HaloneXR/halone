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
    api(project(":core-compositor-common"))
    // Android has org.json built in; other hosts add it themselves.
    compileOnly(libs.json)
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
