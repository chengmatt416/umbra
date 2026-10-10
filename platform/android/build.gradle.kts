plugins {
    id("umbra.android.library")
    id("umbra.serialization")
}

android {
    namespace = "app.umbra.platform.android"
}

dependencies {
    api(project(":core:model"))
    api(project(":platform:api"))
    api(project(":platform:shell"))
    api(project(":platform:vision"))
    api(project(":platform:logs"))
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
}
