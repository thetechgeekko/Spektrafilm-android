// Spektrafilm for Android — app. GPLv3.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing is read from keystore.properties in the project root when present.
// Expected keys: storeFile, storePassword, keyAlias, keyPassword. When the file is
// absent (e.g. CI without secrets) the release build falls back to debug signing.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystorePropsFile.exists() &&
    keystoreProps.getProperty("storeFile") != null

android {
    namespace = "com.spectrafilm.app"
    compileSdk = 34

    // build-tools 35.0.0 is the first whose zipalign supports `-P 16`; AGP uses it to
    // page-align the (uncompressed) bundled .so to 16 KB offsets inside the APK, which
    // is what lets a 16 KB-page device mmap them. Without it the libs are only 4 KB-
    // aligned in the zip and fail to load on Android 15 16 KB devices even when their
    // own ELF segments are 16 KB-aligned.
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.spectrafilm.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 7
        versionName = "0.6.2"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Use the real release keystore when keystore.properties is present,
            // otherwise fall back to debug signing so assembleRelease works in CI.
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation(project(":engine:spektra-core"))
    implementation(project(":lib:libraw"))
    implementation(project(":lib:tiffwriter"))
    implementation(project(":lib:pngwriter"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    testImplementation(libs.junit)
    // Real org.json on the unit-test classpath (the android.jar stub throws "not
    // mocked"); lets Presets JSON round-trip be tested on the plain JVM.
    testImplementation("org.json:json:20231013")
}
