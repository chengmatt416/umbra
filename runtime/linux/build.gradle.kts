plugins {
    id("umbra.jvm.library")
    id("umbra.serialization")
}

dependencies {
    api(project(":core:model"))
    api(project(":platform:api"))
    api(project(":platform:shell"))
    api(project(":platform:privileged"))
    api(project(":runtime:omp"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.xz)

    testImplementation(project(":platform:adb"))
}
