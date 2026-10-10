plugins {
    id("umbra.jvm.library")
    id("umbra.serialization")
}

dependencies {
    api(project(":core:model"))
    api(project(":core:safety"))
    api(project(":core:session"))
    api(project(":core:control"))
    api(project(":core:workspace"))
    api(project(":core:perf"))
    api(project(":core:recovery"))
    api(project(":core:decision"))
    api(project(":runtime:omp"))
    api(project(":runtime:linux"))
    api(project(":tools"))
    api(project(":platform:logs"))
    api(libs.kotlinx.coroutines.core)

    // Live tests drive a real emulator or phone through adb (skipped when none is attached).
    testImplementation(project(":platform:adb"))
    testImplementation(project(":platform:shell"))
    // The real omp binary runs against a scripted OpenAI-compatible server standing in for the model.
    testImplementation(libs.okhttp.mockwebserver)
}
