plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

// A release changes this and nothing else; versionCodeOf (buildSrc/AppVersion.kt) derives the code.
// Keep the marker comment: release-please finds the line by it, and without it a release PR bumps
// CHANGELOG.md but not the APK's version.
val appVersionName = "0.2.0" // x-release-please-version

android {
    namespace = "app.opentv"
    compileSdk = 37

    defaultConfig {
        applicationId = "uk.neutraltone.settee"
        minSdk = 24
        targetSdk = 35
        versionCode = versionCodeOf(appVersionName)
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("upload") {
            // Populated from environment variables in CI. Absent locally, which is why the
            // release build type falls back to the debug key below.
            val storePath = providers.environmentVariable("KEYSTORE_PATH").orNull
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = providers.environmentVariable("KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )

            /*
             * Falls back to the debug key when no keystore is configured.
             *
             * An *unsigned* APK cannot be installed on Android at all — it is rejected before
             * the user sees anything, with an error that explains nothing, so a local release
             * build without the secrets would otherwise be useless.
             *
             * Android will not upgrade between APKs signed with different keys, so the release
             * workflow checks the built APK's certificate and refuses to publish a debug-key
             * build (docs/RELEASING.md).
             */
            signingConfig =
                if (providers.environmentVariable("KEYSTORE_PATH").orNull != null) {
                    signingConfigs.getByName("upload")
                } else {
                    signingConfigs.getByName("debug")
                }
        }
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // Existing findings live in the baseline so CI fails only on new ones; regenerate with
        // ./gradlew updateLintBaseline after fixing some, never to hide a new finding.
        baseline = file("lint-baseline.xml")
        // New strings go in the base file only and fall back to English, so a missing
        // translation is expected, not an error. Dependency versions are Dependabot's job.
        disable +=
            setOf(
                "MissingTranslation",
                "GradleDependency",
                "AndroidGradlePluginVersion",
                "NewerVersionAvailable",
            )
    }

    sourceSets {
        // MigrationTestHelper reads exported schemas from assets, and Robolectric only sees the
        // variant's merged assets. Debug only, so the release APK doesn't carry the JSON.
        getByName("debug").assets.directories += "$projectDir/schemas"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

// The What's new dialog reads the changelog release-please writes, shipped unchanged: filtering it
// down to what a viewer cares about happens at runtime (app.opentv.update.ReleaseNotes), so the
// same parser also serves the GitHub release body in the update dialog.
abstract class CopyChangelog : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val changelog: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        changelog.get().asFile.copyTo(outputDir.file("CHANGELOG.md").get().asFile, overwrite = true)
    }
}

val copyChangelog =
    tasks.register<CopyChangelog>("copyChangelog") {
        changelog.set(rootProject.layout.projectDirectory.file("CHANGELOG.md"))
        outputDir.set(layout.buildDirectory.dir("generated/changelog"))
    }

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyChangelog, CopyChangelog::outputDir)
    }
}

ktlint {
    version.set(libs.versions.ktlint)
    android.set(true)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    // Same deal as the Lint baseline: existing smells are recorded, new ones fail the build.
    baseline = file("detekt-baseline.xml")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore.preferences)
    // Storage Access Framework helpers - write recordings to a plugged-in USB / external
    // drive via a user-granted tree URI, with no storage permission.
    implementation(libs.androidx.documentfile)
    implementation(libs.coil.compose)
    implementation(libs.zxing.core)

    // SMB/CIFS client for recording to a NAS (Synology etc.) and playing those recordings
    // back in-app over the network. Pure-Java SMB2/3, no native bits.
    implementation("com.hierynomus:smbj:0.15.0")

    detektPlugins(libs.compose.rules.detekt)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.room.testing)
    testImplementation(libs.androidx.test.ext.junit)
    // Compose UI tests run on the JVM under Robolectric; the manifest artifact supplies the empty
    // activity createComposeRule() launches.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
