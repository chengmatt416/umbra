plugins {
    id("umbra.jvm.library")
    id("umbra.serialization")
    application
}

dependencies {
    implementation(project(":core:nodes"))
    // The evidence file format, to read an export back (umbra-node evidence-decrypt).
    implementation(project(":core:control"))
}

application {
    applicationName = "umbra-node"
    mainClass.set("app.umbra.node.MainKt")
}
