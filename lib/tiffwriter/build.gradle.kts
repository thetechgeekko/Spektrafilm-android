/*
 * Spektrafilm for Android — lib:tiffwriter build.
 * Copyright (C) 2026 Spektrafilm Android contributors. GPLv3.
 *
 * Android library wrapping the native 16-bit baseline TIFF writer (libsftiff.so,
 * built via CMake/NDK; dependency-free) plus the Kotlin TiffWriter facade.
 *
 * Mirrors lib:libraw's plain-AGP setup (com.android.library + built-in Kotlin +
 * externalNativeBuild CMake + abiFilters) so it configures/builds standalone the
 * moment the project lead adds it to settings.gradle.kts.
 */
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.spectrafilm.tiffwriter"
    compileSdk = 36

    // NDK r28c (28.2.13676358): 16 KB page-size support is the default (r27 introduced it), so
    // native LOAD segments are 16 KB-aligned and
    // ships a 16 KB-aligned libc++_shared.so — required for Android 15's 16 KB page
    // devices. The CMake link flag (CMakeLists.txt) pins the alignment explicitly too.
    ndkVersion = "28.2.13676358"

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
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // No compile dependencies: the writer takes a 16-bit RGB buffer + ICC bytes +
    // path. The caller (feature:film-emulation, a later wave) quantises the engine's
    // display-referred output and supplies the matching ICC profile asset.
    testImplementation(libs.junit)
}
