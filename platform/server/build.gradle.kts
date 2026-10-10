plugins {
    id("umbra.android.library")
    id("umbra.serialization")
}

android {
    namespace = "app.umbra.platform.server"
}

// The server side: classes that run inside an `app_process` started with shell or root rights.
dependencies {
    api(project(":platform:privileged"))
    implementation(libs.kotlinx.coroutines.core)
}
