
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.epicreplay.media"
    // Compose 1.12 needs API 37; 37.2 is the platform installed with the phone's SDK (Android 17).
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
    // The video pipeline (spike S2, versions checked 2026-11-15): cut, speed, crop, held frames, export. The blend of the slow replay is our own effect.
    api(libs.media3.common)
    implementation(libs.media3.effect)
    implementation(libs.media3.transformer)
}
