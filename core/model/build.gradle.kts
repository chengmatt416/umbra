plugins {
    id("umbra.jvm.library")
    id("umbra.serialization")
}

dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
}
