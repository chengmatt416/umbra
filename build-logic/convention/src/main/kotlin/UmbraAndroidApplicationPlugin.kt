import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * The single application module. targetSdk comes from the catalog (28, see ADR 0002) and is
 * set explicitly because AGP 9 no longer defaults it to minSdk.
 */
class UmbraAndroidApplicationPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        extensions.configure<ApplicationExtension> {
            configureAndroidCommon(this)
            defaultConfig.targetSdk = libs.version("targetSdk").toInt()
        }
    }
}
