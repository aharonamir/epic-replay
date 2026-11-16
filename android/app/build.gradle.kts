import org.gradle.api.tasks.Sync

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
    }

    // Signing like voice-reps: the keystore comes from the environment, nothing is committed.
    // Without these variables the release build is unsigned (CI builds only the debug APK).
    val keystore = System.getenv("EPIC_KEYSTORE_PATH")
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

// The shared data is packaged as assets from ../../shared by a build step that selects what the
// app needs and turns the fonts into TrueType. The output is under build/, never committed.
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addStaticSourceDirectory(
            layout.buildDirectory.dir("generated/shared-assets").get().asFile.absolutePath
        )
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
}

val shared = rootProject.layout.projectDirectory.dir("../shared")
val sharedAssets = layout.buildDirectory.dir("generated/shared-assets")
val python = providers.environmentVariable("EPIC_PYTHON").orElse("python3")

val syncSharedAssets by tasks.registering(Sync::class) {
    description = "Packages the shared data the app reads (models, styles, lexicons, copy, contracts, sounds)."
    from(shared) {
        include("models/**", "styles/**", "lexicons/**", "copy/**", "contracts/**")
        include("assets/sfx/**", "assets/music/**", "assets/samples/**", "assets/LICENCES.md")
    }
    into(sharedAssets)
}

val convertFonts by tasks.registering(Exec::class) {
    description = "Converts the shared .woff2 fonts to TrueType (Android cannot load woff2; spike S2)."
    val fonts = shared.dir("assets/fonts")
    val out = sharedAssets.map { it.dir("assets/fonts") } // the shared layout is kept: assets/fonts
    inputs.dir(fonts)
    inputs.file(rootProject.layout.projectDirectory.file("tools/woff2_to_ttf.py"))
    outputs.dir(out)
    dependsOn(syncSharedAssets) // Sync clears its target, so the fonts are written after it
    commandLine(
        python.get(),
        rootProject.layout.projectDirectory.file("tools/woff2_to_ttf.py").asFile.absolutePath,
        fonts.asFile.absolutePath,
        out.get().asFile.absolutePath,
    )
}

tasks.matching { it.name.matches(Regex("merge.*Assets")) }.configureEach {
    dependsOn(syncSharedAssets, convertFonts)
}
