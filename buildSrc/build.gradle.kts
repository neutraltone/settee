plugins {
    `kotlin-dsl`
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

// Gradle 8 stopped running buildSrc's tests as part of the build, and CI only runs the app's.
// Running them straight after the jar restores that: a broken versionCode formula fails every
// build. finalizedBy, not dependsOn, because compiling the tests needs the jar.
tasks.named("jar") {
    finalizedBy("test")
}
