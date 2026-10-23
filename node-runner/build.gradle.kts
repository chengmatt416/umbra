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

distributions {
    main {
        contents {
            into("legal") {
                from(rootProject.file("LICENSE"))
                from(rootProject.file("THIRD_PARTY_NOTICES.md"))
                from(rootProject.file("PRIVACY.md"))
                from(rootProject.file("USAGE_POLICY.md"))
                from(rootProject.file("third_party")) { into("third_party") }
            }
        }
    }
}
