plugins {
    id("umbra.jvm.library")
    id("umbra.serialization")
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)
}
