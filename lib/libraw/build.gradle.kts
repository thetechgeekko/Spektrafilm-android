/*
 * SpectraFilm for Android — lib:libraw build.
 * Copyright (C) 2026 SpectraFilm Android contributors. GPLv3.
 * Decoding powered by LibRaw (LGPL-2.1; GPLv3-compatible, linked dynamically).
 *
 * NOTE: like engine:spektra-core, this module activates at M1, once the
 * ImageToolbox host (with its build-logic convention plugins + version catalog)
 * is seeded into the repo (see tools/bootstrap.md). Until then it documents the
 * intended build: an Android library with a CMake native build producing
 * libsfraw.so, plus the Kotlin RawDecoder facade.
 *
 * LibRaw must be vendored at src/main/cpp/libraw/upstream (git submodule or copy);
 * see README.md and src/main/cpp/CMakeLists.txt.
 */
plugins {
    alias(libs.plugins.image.toolbox.library)
    alias(libs.plugins.image.toolbox.hilt)
}

android {
    namespace = "com.spectrafilm.libraw"

    defaultConfig {
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
        ndk {
            // Match ImageToolbox's supported ABIs.
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    // Primary integration point: hand decoded linear RGB straight to the engine.
    // The engine's LinearImage type lives in engine:spektra-core.
    implementation(projects.engine.spektraCore)

    // Secondary integration point (gallery full-res open) uses Coil 3, provided by
    // the host. Declared compileOnly here so the module stays light; the host app
    // already depends on Coil.
    // compileOnly(libs.coil.core)
}
