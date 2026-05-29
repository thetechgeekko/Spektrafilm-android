// SpectraFilm for Android — engine:spektra-core. GPLv3.
// Android library wrapping the native spektrafilm engine (libspektra.so, built via
// CMake/NDK) plus the Kotlin facade (SpektraEngine / SpektraParams).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.spectrafilm.engine"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // C++ sources test code lives under src/main/cpp/tests but is host-only
    // (standalone g++ host parity tests); it is not part of the Android library.

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
