// Generates Settee's baseline profile on an emulator (#148, docs/PERFORMANCE.md):
//
//   ./gradlew :app:generateBaselineProfile
//
// Never part of CI: it needs a running Android 13+ emulator, and the generated profile is committed
// to app/src/main/generated/baselineProfiles, where ordinary builds pick it up.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "app.opentv.baselineprofile"
    // Compose 1.13 needs the 37.1 minor release; `compileSdk = 37` means 37.0 and fails the AAR check.
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        // BaselineProfileRule needs Android 13 or newer, or a rooted device.
        minSdk = 28
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"
}

// A running emulator, not a Gradle managed device: managed devices have no Android TV image.
baselineProfile {
    useConnectedDevices = true
}

// The generator reads the id of the app build it profiles from here rather than naming it, so the
// two can't drift apart (the app gives these builds an id of their own: app/build.gradle.kts).
androidComponents {
    onVariants { variant ->
        val loader = variant.artifacts.getBuiltArtifactsLoader()
        variant.instrumentationRunnerArguments.put(
            "targetAppId",
            variant.testedApks.map { requireNotNull(loader.load(it)?.applicationId) { "no tested APK" } },
        )
    }
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
