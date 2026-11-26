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
        // themselves are served from the project's release downloads). Limited to its group, so
        // nothing else can be resolved from it.
        maven("https://dl.frostwire.com/maven") {
            content { includeGroup("com.frostwire") }
        }
    }
}

rootProject.name = "OpenTV"
include(":app")
// Generates app/src/main/generated/baselineProfiles on an emulator (docs/PERFORMANCE.md). CI never
// runs it: it needs a device, and the committed profile is what builds use.
include(":baselineprofile")
