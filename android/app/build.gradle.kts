import java.time.LocalDate
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Alpha builds are versioned by date: 2026.10.11-alpha. Pass -Phalone.date=2026.10.11 to pin one.
val buildDate: String = (findProperty("halone.date") as String?)
    ?: LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))

android {
    namespace = "dev.halone.app"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "dev.halone.app"
        minSdk = 29
        targetSdk = 36
        versionCode = buildDate.replace(".", "").toInt()
        versionName = "$buildDate-alpha"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val keystore = findProperty("haloneKeystore") as String?
    val releaseSigning = keystore?.let {
        signingConfigs.create("release") {
            storeFile = file(it)
            storePassword = findProperty("haloneKeystorePassword") as String?
            keyAlias = findProperty("haloneKeyAlias") as String?
            keyPassword = findProperty("haloneKeyPassword") as String?
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = releaseSigning
        }
    }

    // GeckoView's native libraries are ~150 MB per ABI, so each architecture gets its own APK.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    // The system apps, served from https://halone.local/ by WebViewAssetLoader.
    // Built by `pnpm build` in web/; absent until then, and the shell says so.
    sourceSets {
        getByName("main").assets.srcDir("../../web/apps/system/dist")
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

}

dependencies {
    implementation(project(":core-glasses"))
    implementation(project(":core-display"))
    implementation(project(":core-compositor"))
    implementation(project(":engine-webview"))
    implementation(project(":engine-plugin"))
    implementation(project(":core-shell"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.webkit)
    implementation(libs.geckoview)

    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
