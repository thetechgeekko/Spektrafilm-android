// Spektrafilm for Android — macrobenchmark. GPLv3.
//
// Startup / frame-timing benchmarks and the Baseline Profile generator (#459). A test-only
// module: it drives the app's `benchmark` build type (release code, debug-key signed) on a
// connected device and is never packaged into the app. Run on a device, never on CI:
//
//   ./gradlew :macrobenchmark:connectedBenchmarkAndroidTest
//   ./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
//       -Pandroid.testInstrumentationRunnerArguments.class=com.spectrafilm.macrobenchmark.BaselineProfileGenerator
//
// Results and the generated profile land in
// macrobenchmark/build/outputs/connected_android_test_additional_output/.
plugins {
    alias(libs.plugins.android.test)
}

android {
    namespace = "com.spectrafilm.macrobenchmark"
    compileSdk = 36

    defaultConfig {
        // BaselineProfileRule needs API 28+ (33+ without root); the sample image is written
        // through MediaStore's scoped API, which is 29+. The app itself stays minSdk 24.
        minSdk = 29
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        // Matches the app's `benchmark` build type; library modules fall back to release.
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    // Same committed public debug key the app's benchmark build is signed with.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"
    // The test APK instruments the app process from outside (macrobenchmark requirement).
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}

// Only the benchmark variant is meaningful; a debug test APK would measure a debuggable app.
androidComponents {
    beforeVariants(selector().all()) { it.enable = it.buildType == "benchmark" }
}
