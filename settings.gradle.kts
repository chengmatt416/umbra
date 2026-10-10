pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        // libsu is only published on JitPack.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.topjohnwu.libsu") }
        }
    }
}

rootProject.name = "umbra"

include(":app")
include(":core:model")
include(":core:safety")
include(":core:session")
include(":core:workspace")
include(":core:perf")
include(":core:recovery")
include(":core:decision")
include(":core:orchestrator")
include(":core:control")
include(":core:nodes")
include(":runtime:omp")
include(":platform:api")
include(":platform:vision")
include(":platform:logs")
include(":platform:shell")
include(":platform:adb")
include(":platform:android")
include(":platform:privileged")
include(":platform:server")
include(":runtime:linux")
include(":tools")
include(":node-runner")
