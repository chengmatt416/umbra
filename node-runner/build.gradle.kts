plugins {
    id("umbra.jvm.library")
    id("umbra.serialization")
    application
}

dependencies {
    implementation(project(":core:nodes"))
}

application {
    applicationName = "umbra-node"
    mainClass.set("app.umbra.node.MainKt")
}
