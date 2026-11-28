pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // jlibtorrent, the torrent engine's library (ADR 0012), is published only here (the files
        // themselves are served from the project's release downloads).
        // Exclusive both ways: nothing else is resolved from it, and it's never asked for at Maven
        // Central first. Central doesn't have it, and a busy Central answers that lookup with 429
        // rather than 404, which failed CI's whole build.
        exclusiveContent {
            forRepository { maven("https://dl.frostwire.com/maven") }
            filter { includeGroup("com.frostwire") }
        }
    }
}

rootProject.name = "OpenTV"
include(":app")
// Generates app/src/main/generated/baselineProfiles on an emulator (docs/PERFORMANCE.md). CI never
// runs it: it needs a device, and the committed profile is what builds use.
include(":baselineprofile")
