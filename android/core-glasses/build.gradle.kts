plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.halone.glasses"
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
    api(project(":core-scene"))
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
    // android.jar's org.json is a stub, and `isReturnDefaultValues` turns every
    // stubbed call into a silent null rather than an error — which for a parser
    // means tests that pass while parsing nothing. A real implementation on the
    // test classpath is what makes the calibration parser testable at all.
    testImplementation(libs.json)
}
