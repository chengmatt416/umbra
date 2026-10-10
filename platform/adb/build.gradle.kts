plugins {
    id("umbra.jvm.library")
    id("umbra.serialization")
}

// Host-side only: drives a phone or emulator from a development machine over adb. The Android app
// never depends on this module.
dependencies {
    api(project(":platform:api"))
    api(project(":platform:shell"))
    api(project(":platform:vision"))
    api(project(":platform:privileged"))
    api(libs.kotlinx.coroutines.core)
}
