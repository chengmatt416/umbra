import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}

group = "app.umbra.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.kotlin.composeGradlePlugin)
    compileOnly(libs.kotlin.serializationGradlePlugin)
}

gradlePlugin {
    plugins {
        register("umbraJvmLibrary") {
            id = "umbra.jvm.library"
            implementationClass = "UmbraJvmLibraryPlugin"
        }
        register("umbraAndroidLibrary") {
            id = "umbra.android.library"
            implementationClass = "UmbraAndroidLibraryPlugin"
        }
        register("umbraAndroidApplication") {
            id = "umbra.android.application"
            implementationClass = "UmbraAndroidApplicationPlugin"
        }
        register("umbraCompose") {
            id = "umbra.compose"
            implementationClass = "UmbraComposePlugin"
        }
        register("umbraSerialization") {
            id = "umbra.serialization"
            implementationClass = "UmbraSerializationPlugin"
        }
    }
}
