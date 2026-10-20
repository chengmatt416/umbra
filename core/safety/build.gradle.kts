plugins {
    id("umbra.jvm.library")
    id("umbra.serialization")
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:decision"))
    api(libs.kotlinx.coroutines.core)
}
