plugins {
    id("umbra.jvm.library")
    id("umbra.serialization")
}

// Client side of the privileged helper: a process running as the shell user (or root) that the app
// talks to over a loopback socket. Pure JVM so the same code is tested from a development machine.
dependencies {
    api(project(":core:model"))
    api(project(":platform:api"))
    api(project(":platform:shell"))
    api(libs.kotlinx.coroutines.core)
}
