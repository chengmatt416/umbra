plugins {
    id("umbra.jvm.library")
    id("umbra.serialization")
}

dependencies {
    api(project(":core:model"))
    api(project(":core:safety"))
    api(project(":core:decision"))
    api(project(":platform:api"))
    api(project(":platform:vision"))
    api(project(":platform:logs"))
    api(project(":runtime:omp"))
    api(libs.kotlinx.coroutines.core)

    // The integration tests drive a real device or emulator through adb (skipped when none is attached).
    testImplementation(project(":platform:adb"))
    testImplementation(project(":platform:shell"))
}
