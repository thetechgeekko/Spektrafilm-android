/*
 * SpectraFilm for Android — lib:tiffwriter build.
 * Copyright (C) 2026 SpectraFilm Android contributors. GPLv3.
 *
 * Android library wrapping the native 16-bit baseline TIFF writer (libsftiff.so,
 * built via CMake/NDK; dependency-free) plus the Kotlin TiffWriter facade.
 *
 * Mirrors lib:libraw's plain-AGP setup (com.android.library + kotlin.android +
 * externalNativeBuild CMake + abiFilters) so it configures/builds standalone the
 * moment the project lead adds it to settings.gradle.kts.
 */
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.spectrafilm.tiffwriter"
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // No compile dependencies: the writer takes a 16-bit RGB buffer + ICC bytes +
    // path. The caller (feature:film-emulation, a later wave) quantises the engine's
    // display-referred output and supplies the matching ICC profile asset.
}
