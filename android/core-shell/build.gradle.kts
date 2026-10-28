plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.halone.shell"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(project(":core-compositor"))
    api(project(":engine-webview"))
    api(project(":engine-plugin"))
    implementation(libs.androidx.webkit)
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
