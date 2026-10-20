import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import javax.inject.Inject
import org.gradle.api.file.FileSystemOperations

plugins {
    id("umbra.android.application")
    id("umbra.compose")
    id("umbra.serialization")
}

android {
    namespace = "app.umbra.agent"

    defaultConfig {
        applicationId = "app.umbra.agent"
        // `-Pumbra.versionCode=… -Pumbra.versionName=…` override them, for testing the updater with a later build.
        versionCode = providers.gradleProperty("umbra.versionCode").orNull?.toInt() ?: 10
        versionName = providers.gradleProperty("umbra.versionName").orNull ?: "0.4.3"
    }

    // Releases are signed with the key in UMBRA_KEYSTORE (see .github/workflows/release.yml); without it the
    // release build falls back to the debug key, which is fine for installing on your own phone.
    signingConfigs {
        create("release") {
            System.getenv("UMBRA_KEYSTORE")?.let { path ->
                storeFile = file(path)
                storePassword = System.getenv("UMBRA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("UMBRA_KEY_ALIAS") ?: "umbra"
                keyPassword = System.getenv("UMBRA_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            // Not minified: the first release favours a build that behaves exactly like the tested debug one.
            isMinifyEnabled = false
            signingConfig = if (System.getenv("UMBRA_KEYSTORE") != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    packaging {
        // The runtime ships executables and LD_PRELOAD shims as jniLibs; they must be extracted to
        // nativeLibraryDir so they exist as real files (same approach as upstream Termux).
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // The Linux image and the add-on repository are compressed already, and the app streams them: stored as they are,
    // they cost no build time and can be read straight out of the APK.
    androidResources {
        noCompress += listOf("xz", "tar")
    }
}

// ---- the Linux environment inside the APK (ADR 0011) ----
// `-Pumbra.bundleLinux=false` builds without it (a quick development build; the app then offers file and URL
// installs). Otherwise the archives from runtime-build/rootfs/build.sh must exist: an APK that silently lacks the
// environment is worse than a failed build.
val bundleLinux = providers.gradleProperty("umbra.bundleLinux").map { it.toBoolean() }.orElse(true)
val linuxVersion = providers.gradleProperty("umbra.linuxVersion").orElse("dev")

abstract class StageLinuxImage : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val rootfs: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val packs: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun stage() {
        val dir = output.dir("linux").get().asFile
        files.delete { delete(dir) }
        dir.mkdirs()
        files.copy { from(rootfs); into(dir); rename { "rootfs.tar.xz" } }
        files.copy { from(packs); into(dir); rename { "packs.tar" } }
        fun sha(file: File) = File(file.path + ".sha256").readText().trim().substringBefore(' ')
        val r = rootfs.get().asFile
        val p = packs.get().asFile
        // What the app needs to know without opening the archives: sizes for progress and free-space checks, digests
        // to tell an installed environment from a newer one, and the component versions for the set-up screen.
        File(dir, "bundle.json").writeText(
            """{"rootfs":{"bytes":${r.length()},"sha256":"${sha(r)}"},"packs":{"bytes":${p.length()},"sha256":"${sha(p)}"},"manifest":${manifest.get().asFile.readText().trim()}}""" + "\n",
        )
    }
}

// The helper scripts and package lists of the Linux environment (guest/) travel inside the app too, so the app
// can keep the copies in the environment in step with itself (see GuestFiles in runtime:linux).
abstract class StageGuestFiles : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val source: DirectoryProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun stage() {
        files.sync {
            from(source)
            into(output.dir("guest"))
        }
    }
}

extensions.configure<ApplicationAndroidComponentsExtension> {
    onVariants { variant ->
        val stage = tasks.register<StageGuestFiles>("stageGuestFiles${variant.name.replaceFirstChar { it.uppercase() }}") {
            source.set(rootProject.layout.projectDirectory.dir("guest"))
            output.set(layout.buildDirectory.dir("generated/guest-assets/${variant.name}"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(stage, StageGuestFiles::output)

        // proot for phones without root (ADR 0012): Termux's arm64 build, laid out by runtime-build/proot/fetch.sh.
        val proot = rootProject.layout.projectDirectory.dir("build/proot/jniLibs")
        check(proot.file("arm64-v8a/libproot.so").asFile.exists()) {
            "proot is missing (${proot.asFile}): run runtime-build/proot/fetch.sh"
        }
        variant.sources.jniLibs?.addStaticSourceDirectory(proot.asFile.path)

        if (bundleLinux.get()) {
            val out = rootProject.layout.projectDirectory.dir("build/rootfs")
            val version = linuxVersion.get()
            val rootfsFile = out.file("umbra-rootfs-$version-arm64.tar.xz")
            val packsFile = out.file("umbra-packs-$version-arm64.tar")
            for (needed in listOf(rootfsFile, packsFile, out.file("umbra-rootfs-$version-arm64.tar.xz.sha256"), out.file("umbra-packs-$version-arm64.tar.sha256"), out.file("manifest.json"))) {
                check(needed.asFile.exists()) {
                    "The APK carries the Linux environment, but ${needed.asFile} is missing. Build it with runtime-build/rootfs/build.sh $version, or build without it: -Pumbra.bundleLinux=false"
                }
            }
            val linux = tasks.register<StageLinuxImage>("stageLinuxImage${variant.name.replaceFirstChar { it.uppercase() }}") {
                rootfs.set(rootfsFile)
                packs.set(packsFile)
                manifest.set(out.file("manifest.json"))
                output.set(layout.buildDirectory.dir("generated/linux-assets/${variant.name}"))
            }
            variant.sources.assets?.addGeneratedSourceDirectory(linux, StageLinuxImage::output)
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:safety"))
    implementation(project(":core:decision"))
    implementation(project(":core:session"))
    implementation(project(":core:workspace"))
    implementation(project(":core:perf"))
    implementation(project(":core:recovery"))
    implementation(project(":core:orchestrator"))
    implementation(project(":core:control"))
    implementation(project(":runtime:linux"))
    implementation(project(":platform:api"))
    implementation(project(":platform:shell"))
    implementation(project(":platform:logs"))
    implementation(project(":platform:vision"))
    implementation(project(":platform:android"))
    implementation(project(":platform:privileged"))
    implementation(project(":platform:server"))
    implementation(project(":tools"))
    implementation(project(":runtime:omp"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.zxing.core)
    implementation(libs.kyant.backdrop)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}
