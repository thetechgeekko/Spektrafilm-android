/*
 * SpectraFilm for Android — engine:spektra-core build.
 * GPLv3.
 *
 * NOTE: this activates at M1, once the ImageToolbox host (with its build-logic
 * convention plugins + version catalog) is seeded into the repo (see tools/bootstrap.md).
 * Until then it documents the intended build: an Android library with a CMake native
 * build producing libspektra.so, plus the Kotlin facade.
 */
plugins {
    alias(libs.plugins.image.toolbox.library)
    alias(libs.plugins.image.toolbox.hilt)
}

android {
    namespace = "com.spectrafilm.engine"

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
    // Engine is intentionally light on app deps; it consumes linear buffers + params.
    implementation(projects.core.domain)
    // M2: implementation(projects.lib.libraw) // or link LibRaw natively in CMake
}
