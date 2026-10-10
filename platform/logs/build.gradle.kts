plugins {
    id("umbra.jvm.library")
    id("umbra.serialization")
}

dependencies {
    api(project(":core:model"))
    api(project(":platform:api"))
    api(libs.kotlinx.coroutines.core)
}
