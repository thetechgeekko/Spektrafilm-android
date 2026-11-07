// Spektrafilm for Android — Baseline Profile generator and Macrobenchmarks (#459). GPLv3.
//
// Record the profiles on a connected device (API 28+; no root needed on API 33+):
//   ./gradlew :app:generateBaselineProfile
// which writes app/src/main/generated/baselineProfiles/{baseline,startup}-prof.txt.
// Measure startup and frames on the benchmarkRelease build:
//   ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.spectrafilm.baselineprofile"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        // Profile capture needs API 28+; Macrobenchmark itself needs 23+.
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

// Runs on whatever device adb sees (the maintainer's phone); no Gradle-managed emulator.
baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
