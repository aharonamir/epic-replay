// The JNI bridge, the llama.cpp build and the downloads (T409, T410). Empty for now.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.epicreplay.engine"
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
}
