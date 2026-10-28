plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.halone.compositor"
    compileSdk = 37
    compileSdkMinor = 2
    ndkVersion = "27.1.12297006"

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

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    defaultConfig {
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            val fixtures = rootProject.file("fixtures/scene")
            val updating = project.hasProperty("updateSceneGoldens")
            val sceneSources = rootProject.file("android/core-scene/src")
            val scenePackageHash = rootProject.file("web/packages/scene/dist/source-hash")
            it.inputs.dir(fixtures)
            it.inputs.dir(sceneSources)
            it.inputs.file(scenePackageHash)
            it.systemProperty("sceneFixtures", fixtures.path)
            it.systemProperty("sceneSources", sceneSources.path)
            it.systemProperty("scenePackageHash", scenePackageHash.path)
            it.systemProperty("updateSceneGoldens", updating.toString())
            if (updating) it.outputs.upToDateWhen { false }
            it.mustRunAfter(":core-scene:syncWebPackage")
        }
    }
}

dependencies {
    api(project(":core-glasses"))
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
