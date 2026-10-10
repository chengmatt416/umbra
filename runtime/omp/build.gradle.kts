plugins {
    id("umbra.jvm.library")
    id("umbra.serialization")
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)

    // Integration tests run the real omp binary against a scripted OpenAI-compatible server.
    testImplementation(libs.okhttp.mockwebserver)
    // Parses the YAML we generate, to prove omp's loader will accept it.
    testImplementation(libs.snakeyaml)
}
