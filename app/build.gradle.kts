import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.baselineprofile)
    alias(libs.plugins.sentry.android)
}

// A release changes this and nothing else; versionCodeOf (buildSrc/AppVersion.kt) derives the code.
// Keep the marker comment: release-please finds the line by it, and without it a release PR bumps
// CHANGELOG.md but not the APK's version.
val appVersionName = "1.7.0" // x-release-please-version

/** local.properties, which git ignores, read once for the built-in keys below; empty without one. */
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

/**
 * A built-in key: its secret's environment variable in CI, else [property] in local.properties, else
 * blank. One that's set but in the wrong format stops the build (checkedBuiltInKey, buildSrc), rather
 * than ship looking plausible as v1.5.0's OpenSubtitles key did (#628).
 */
fun builtInKey(key: BuiltInKey, property: String): String = checkedBuiltInKey(
    key,
    providers.environmentVariable(key.secret).orNull ?: localProperties.getProperty(property) ?: "",
)

/*
 * Settee's own OpenSubtitles key (one per app: OpenSubtitles bans apps that make viewers bring their
 * own). From the OPENSUBTITLES_API_KEY secret in CI, or opensubtitles.apiKey in local.properties,
 * which git ignores. Never committed. Blank leaves OpenSubtitles out of the app entirely.
 */
val openSubtitlesApiKey: String = builtInKey(BuiltInKey.OPENSUBTITLES, "opensubtitles.apiKey")

/*
 * Settee's own TMDB key: viewers no longer bring their own. From the TMDB_API_KEY secret in CI, or
 * tmdb.apiKey in local.properties, which git ignores. Never committed. Either of TMDB's forms works,
 * the v3 API Key or the v4 Read Access Token (a JWT). Blank leaves TMDB unasked. It can't be kept
 * secret in an APK, and needn't be: TMDB rate-limits by IP address.
 */
val tmdbApiKey: String = builtInKey(BuiltInKey.TMDB, "tmdb.apiKey")

/*
 * Settee's Sentry DSN, for crash reports a viewer has turned on (ADR 0009). From the SENTRY_DSN secret
 * in CI, or sentry.dsn in local.properties, which git ignores. Only release builds (and the .pr
 * builds made from them) get it: debug builds always have a blank one, and blank means crash
 * reporting doesn't exist in that build. A DSN can always be pulled out of an APK; it can only send,
 * and Sentry's own filters limit misuse.
 */
val sentryDsn: String = builtInKey(BuiltInKey.SENTRY_DSN, "sentry.dsn")

/*
 * Lets the release build upload its R8 mapping to Sentry, so a crash report's stack trace reads as
 * Settee's own class and method names (#577). From the SENTRY_AUTH_TOKEN secret, which only
 * release.yml passes; blank everywhere else (this Mac, forks, PR CI), where the upload is skipped.
 * Never in local.properties: a local release build is a .pr build, whose mapping isn't a release's.
 */
val sentryAuthToken: String = providers.environmentVariable("SENTRY_AUTH_TOKEN").orNull.orEmpty()

// The profile generator's builds of the app take this plus a suffix (androidComponents below).
val releaseApplicationId = "uk.neutraltone.settee"

// A release build made without the release keystore, which only CI has: this Mac's, for trying a
// branch on a device. It falls back to the debug key, so it gets its own id, label and badge.
val isLocalRelease = providers.environmentVariable("KEYSTORE_PATH").orNull == null

// Robolectric runs each test on an android-all jar, which it otherwise downloads from Maven Central
// itself the first time a test JVM needs it, into ~/.m2, which CI doesn't keep. Maven Central turns
// those big downloads away at times (HTTP 429), and one refused failed every test class in that JVM
// (262 in one release run). Resolved here they're Gradle dependencies instead, cached with the rest
// (setup-gradle on CI), and Robolectric is run offline on them. The versions are Robolectric's own
// for the SDKs the tests pin with @Config(sdk = ...), from its DefaultSdkProvider: a Robolectric bump
// that changes them fails every test naming the jar it couldn't find, so move them with it.
// One configuration each: they're versions of one module, of which Gradle keeps only the newest.
val robolectricSdks = mapOf(
    // Nearly every test.
    34 to "14-robolectric-10818077-i7",
    // Fire OS 6, for the few that check an old device.
    25 to "7.1.0_r7-robolectric-r1-i7",
).map { (sdk, version) ->
    configurations.create("robolectricSdk$sdk") { isTransitive = false }.also {
        dependencies.add(it.name, "org.robolectric:android-all-instrumented:$version")
    }
}
val robolectricSdkDir = layout.buildDirectory.dir("robolectric-sdks")
val syncRobolectricSdks =
    tasks.register<Sync>("syncRobolectricSdks") {
        from(robolectricSdks)
        into(robolectricSdkDir)
    }

android {
    namespace = "app.opentv"
    compileSdk = 37

    defaultConfig {
        applicationId = releaseApplicationId
        minSdk = 24
        targetSdk = 35
        versionCode = versionCodeOf(appVersionName)
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Safe in a Java string as they are: builtInKey only lets through letters, digits and . - _
        buildConfigField("String", "OPENSUBTITLES_API_KEY", "\"$openSubtitlesApiKey\"")
        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")
        // Blank here, so debug builds never report; the release build type sets the real one.
        buildConfigField("String", "SENTRY_DSN", "\"\"")
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
            // A local release build is its own app, "Settee Release" with a RELEASE badge, beside
            // the real one: Android won't update an app with an APK signed by another key, so it
            // could never replace the release install anyway, and on a device it needs telling
            // apart from both that and the debug build. The badge is in src/local (sourceSets).
            if (isLocalRelease) applicationIdSuffix = ".pr"
            // builtInKey has already refused anything but https://<key>@<host>/<project id>.
            buildConfigField("String", "SENTRY_DSN", "\"$sentryDsn\"")
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
                if (isLocalRelease) {
                    signingConfigs.getByName("debug")
                } else {
                    signingConfigs.getByName("upload")
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
        jniLibs {
            // The torrent engine's native library is 14-17 MB a copy. Only the ARM ABIs that Fire TV and
            // phones use are kept (ADR 0012): an x86_64 copy for the emulator isn't worth its size, as
            // this Mac's emulators are arm64 and Robolectric never loads it. An x86 device or emulator
            // gets TorrentFailure.UNSUPPORTED_DEVICE. Not abiFilters, which would drop the x86 builds of
            // AndroidX's own small libraries with it.
            excludes += setOf("lib/x86/libjlibtorrent.so", "lib/x86_64/libjlibtorrent.so")
            // Compressed, and extracted at install. Stored uncompressed (AGP's default) the APK carried
            // both ABIs' 31.6 MB as they are, and the box keeps the whole APK; compressed, the download
            // is far smaller and only the box's own ABI is extracted, so it takes less space too.
            useLegacyPackaging = true
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
        // Only the build signed with the debug key wears the RELEASE badge, so the one that ships
        // can't: release.yml has the keystore, and refuses a debug-key APK besides.
        if (isLocalRelease) {
            getByName("release") {
                manifest.srcFile("src/local/AndroidManifest.xml")
                res.directories += "src/local/res"
            }
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            // Live pause's length follows the heap (BufferPlan), so the screen tests fix it at a
            // device with plenty of memory: two minutes, whatever machine runs them.
            all {
                it.maxHeapSize = "4g"
                it.dependsOn(syncRobolectricSdks)
                it.systemProperty("robolectric.offline", "true")
                it.systemProperty("robolectric.dependency.dir", robolectricSdkDir.get().asFile.absolutePath)
                // BaselineProfileTest reads the committed profile, so a regenerated one reruns it.
                it.inputs
                    .dir("src/main/generated/baselineProfiles")
                    .withPathSensitivity(PathSensitivity.RELATIVE)
                    .optional()
            }
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
    // The baseline profile generator's builds of the app (the plugin's nonMinifiedRelease and
    // benchmarkRelease) get their own id. With the release app's, a run on a device that has Settee
    // installed would replace it, and the test run's clean-up would then uninstall it with all its
    // providers, Favourites and progress.
    onVariants { variant ->
        if (variant.buildType in setOf("nonMinifiedRelease", "benchmarkRelease")) {
            variant.applicationId.set("$releaseApplicationId.profiling")
        }
    }
}

// The profile is generated by hand on an emulator (docs/PERFORMANCE.md) and committed, so an
// ordinary build never needs a device. Merged into main rather than per variant: the generator
// runs against the non-minified release build, and R8 maps its names onto the shipped one.
baselineProfile {
    mergeIntoMain = true
    automaticGenerationDuringBuild = false
    saveInSrc = true
}

/*
 * The Sentry Gradle plugin, for one job: the release build's R8 mapping (#577). It bakes an id for the
 * mapping into the APK's assets, which the SDK puts on every report (CrashScrubbing keeps it, and
 * nothing else of the debug metadata), and uploads the mapping to Sentry under that id. Sentry matches
 * a report to its mapping by that id, so a report carrying release VERSION_NAME (CrashReports) reads
 * with that release's names; it needs no release name of its own (sentry-cli no longer takes one).
 *
 * Everything else the plugin does is off: it would rewrite bytecode (tracing, SDK optimisations),
 * add Sentry modules or the SDK's auto-init back (ADR 0009 starts Sentry by hand, only with consent),
 * ship source code or a dependency list in the APK, or report the build itself to Sentry.
 */
sentry {
    includeProguardMapping.set(true)
    autoUploadProguardMapping.set(sentryAuthToken.isNotBlank())
    authToken.set(sentryAuthToken.ifBlank { null })
    // Which Sentry project gets it: release.yml's SENTRY_ORG and SENTRY_PROJECT repository variables.
    org.set(providers.environmentVariable("SENTRY_ORG"))
    projectName.set(providers.environmentVariable("SENTRY_PROJECT"))
    // Only the build that ships: not debug, and not the baseline profile generator's builds.
    ignoredBuildTypes.set(setOf("debug", "nonMinifiedRelease", "benchmarkRelease"))
    tracingInstrumentation { enabled.set(false) }
    autoInstallation { enabled.set(false) }
    runtimeOptimizations { enabled.set(false) }
    includeDependenciesReport.set(false)
    includeSourceContext.set(false)
    uploadNativeSymbols.set(false)
    includeNativeSources.set(false)
    sizeAnalysis { enabled.set(false) }
    distribution { enabled.set(false) }
    snapshots { enabled.set(false) }
    verifyOpenTelemetryVersions.set(false)
    telemetry.set(false)
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

/*
 * The ways a test used to wait for the app's work (#616, ADR 0008), banned in test sources by
 * config/detekt/test-waits.yml. ForbiddenMethodCall needs type resolution, which plain `detekt`
 * hasn't got (it skips the rule without a word), and detekt 1.23's per-variant tasks don't exist
 * under AGP 9's built-in Kotlin, so this task gives it the unit tests' classpath itself. The main
 * sources are analysed too, only so that calls into the app resolve; the rule looks at tests alone.
 */
val detektTestWaits by tasks.registering(io.gitlab.arturbosch.detekt.Detekt::class) {
    description = "Fails a test that waits on wall-clock time, a manual looper idle or the process's graph."
    group = "verification"
    setSource(files("src/main/java", "src/test/java"))
    include("**/*.kt")
    config.setFrom(rootProject.file("config/detekt/test-waits.yml"))
    buildUponDefaultConfig = false
    baseline.set(file("detekt-baseline.xml"))
    jvmTarget = "17"
    classpath.setFrom(
        configurations.named("debugUnitTestCompileClasspath").map { compile ->
            compile.incoming.artifactView {
                attributes { attribute(Attribute.of("artifactType", String::class.java), "android-classes-jar") }
            }.files
        },
        androidComponents.sdkComponents.bootClasspath,
    )
    reports {
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.file("reports/detekt/test-waits.html"))
        xml.required.set(false)
        txt.required.set(false)
        sarif.required.set(false)
        md.required.set(false)
    }
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
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

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
    implementation(libs.sentry.android.core)
    implementation(libs.zxing.core)
    // The torrent engine (ADR 0012, player/torrent). Only its ARM native libraries are packaged.
    implementation(libs.jlibtorrent.android)
    // Installs the baseline profile on a sideloaded APK, which no app store does for us: on the
    // first run, or at the next background dexopt (docs/PERFORMANCE.md).
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))

    // SMB/CIFS client for recording to a NAS (Synology etc.) and playing those recordings
    // back in-app over the network. Pure-Java SMB2/3, no native bits.
    implementation("com.hierynomus:smbj:0.15.0")

    detektPlugins(libs.compose.rules.detekt)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.room.testing)
    testImplementation(libs.androidx.test.ext.junit)
    // Compose UI tests run on the JVM under Robolectric; the manifest artifact supplies the empty
    // activity createComposeRule() launches.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    // Screenshots (#44): Roborazzi captures them inside the Robolectric UI tests, and only when
    // recording (./gradlew recordRoborazziDebug); a plain test run skips the capture.
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.coil.test)
    // The player tests (#68) drive a real ExoPlayer with fake media sources and a fake clock.
    testImplementation(libs.media3.test.utils)
    testImplementation(libs.media3.test.utils.robolectric)
}
