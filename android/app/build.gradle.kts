import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.epicreplay"
    // Compose 1.12 needs API 37; 37.2 is the platform installed with the phone's SDK (Android 17).
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "app.epicreplay"
        minSdk = 28 // spec 004, section 7: Android 9+
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Signing like voice-reps: the keystore comes from the environment, nothing is committed.
    // Without them a release task stops with a message (CI builds only the debug APK).
    val signingVars = listOf("EPIC_KEYSTORE_PATH", "EPIC_KEYSTORE_PASSWORD", "EPIC_KEY_ALIAS", "EPIC_KEY_PASSWORD")
    val given = signingVars.filter { !System.getenv(it).isNullOrEmpty() }
    // All four or none: a half-set environment is a mistake, and a release is never unsigned by accident.
    check(given.isEmpty() || given.size == signingVars.size) {
        "Signing needs all of $signingVars; missing: ${signingVars - given.toSet()}"
    }
    val keystore = System.getenv("EPIC_KEYSTORE_PATH").takeIf { given.isNotEmpty() }
    gradle.taskGraph.whenReady {
        val wantsRelease = allTasks.any { it.project == project && it.name.matches(Regex("(assemble|bundle|package)Release.*")) }
        check(!wantsRelease || keystore != null) {
            "A release build must be signed: set $signingVars (use assembleDebug for an unsigned build)"
        }
    }
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("EPIC_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("EPIC_KEY_ALIAS")
                keyPassword = System.getenv("EPIC_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    implementation(project(":core"))
    implementation(project(":engine"))
    implementation(project(":media"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

/**
 * Packages the shared data the app reads (models, styles, lexicons, copy, contracts, sounds) as
 * assets, keeping the shared layout, and turns the shared .woff2 fonts into TrueType (Android cannot
 * load woff2; spike S2). The output is under build/, never committed.
 */
// Not cacheable: its result depends on the installed font tools, which no input can identify.
abstract class PrepareSharedAssets : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val shared: DirectoryProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val fontScript: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val fontRequirements: RegularFileProperty
    @get:Input abstract val python: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Inject abstract val fs: FileSystemOperations
    @get:Inject abstract val exec: ExecOperations

    @TaskAction
    fun run() {
        val out = outputDir.get().asFile
        fs.delete { delete(out) } // nothing stale survives a change in shared/
        fs.copy {
            from(shared) {
                include("models/**", "styles/**", "lexicons/**", "copy/**", "contracts/**")
                include("assets/sfx/**", "assets/music/**", "assets/samples/**", "assets/LICENCES.md")
            }
            into(out)
        }
        exec.exec {
            commandLine(
                python.get(),
                fontScript.get().asFile.absolutePath,
                shared.get().dir("assets/fonts").asFile.absolutePath,
                out.resolve("assets/fonts").absolutePath,
            )
        }
    }
}

val prepareSharedAssets by tasks.registering(PrepareSharedAssets::class) {
    shared.set(rootProject.layout.projectDirectory.dir("../shared"))
    fontScript.set(rootProject.layout.projectDirectory.file("tools/woff2_to_ttf.py"))
    fontRequirements.set(rootProject.layout.projectDirectory.file("tools/requirements.txt"))
    python.set(providers.environmentVariable("EPIC_PYTHON").orElse("python3"))
    outputDir.set(layout.buildDirectory.dir("generated/shared-assets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(prepareSharedAssets, PrepareSharedAssets::outputDir)
    }
}
