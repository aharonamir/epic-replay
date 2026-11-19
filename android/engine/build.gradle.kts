// The JNI bridge, the llama.cpp build (native/CMakeLists.txt) and the on-device backend (T409); downloads come with T410.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.epicreplay.engine"
    // Compose 1.12 needs API 37; 37.2 is the platform installed with the phone's SDK (Android 17).
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    // The NDK and CMake of the spike (docs/android-spikes.md); Gradle installs them once the licences are accepted.
    ndkVersion = "29.0.14206865"
    defaultConfig {
        minSdk = 28
        ndk { abiFilters += "arm64-v8a" } // the phones the app targets (spec 004 section 7)
        externalNativeBuild {
            cmake {
                // Always optimised, also in a debug build: an unoptimised llama.cpp is unusably slow.
                // The ARM flags are those of voice-reps and spike S1 (spec 004 FR-012).
                // -Pepic.llama.dir=/path builds from a local llama.cpp checkout at the pinned commit instead of fetching it.
                providers.gradleProperty("epic.llama.dir").orNull?.let { arguments += "-DFETCHCONTENT_SOURCE_DIR_LLAMA=$it" }
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_C_FLAGS=-march=armv8.2-a+dotprod",
                    "-DCMAKE_CXX_FLAGS=-march=armv8.2-a+dotprod",
                )
                targets += "epic_engine"
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("../native/CMakeLists.txt")
            version = "3.31.5"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
}
