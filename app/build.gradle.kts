// Spektrafilm for Android — app. GPLv3.
import java.util.Properties
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // @Serializable navigation routes (#274).
    alias(libs.plugins.kotlin.serialization)
    // Recorded Baseline + Startup Profiles from :baselineprofile (#459).
    alias(libs.plugins.androidx.baselineprofile)
}

// Optional local release signing is read from keystore.properties in the project root.
// Expected keys: storeFile, storePassword, keyAlias, keyPassword. Without that file,
// assembleRelease deliberately emits an unsigned APK; it never falls back to the
// public debug key. The protected release workflow signs that qualified artifact
// later with pinned Android build-tools, outside Gradle's dependency graph.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystorePropsFile.exists() &&
    keystoreProps.getProperty("storeFile") != null

android {
    namespace = "com.spectrafilm.app"
    // The app module strips the packaged .so with this NDK's llvm-strip. AGP 9 defaults to
    // NDK r28.2 (not installed); left unpinned it packages every native library unstripped
    // (libspektra.so 0.9 MB -> 11.6 MB). Pin the same NDK as the native modules (r28c since
    // #187; r27 before that).
    ndkVersion = "28.2.13676358"
    // 37 for the toolchain only: set when material3 1.5.0-alpha28 (Material 3 Expressive) and
    // the Compose 1.13 alphas it pulled required compiling against android-37. material3 is now
    // 1.5.0-rc01 on stable Compose 1.12 (#292, #384); whether 36 would do again is untested,
    // so 37 stays until a build proves otherwise. targetSdk stays 36 --
    // that is the Play-policy surface validated by #171, and compileSdk does not change runtime
    // behaviour, only which APIs are visible at compile time.
    compileSdk = 37

    // build-tools 36.0.0 is the AGP 9.3 minimum (35.0.0 was the first whose zipalign
    // supports `-P 16`); the CI/release 16 KB gates call this same build-tools zipalign to
    // page-align the (uncompressed) bundled .so to 16 KB offsets inside the APK, which
    // is what lets a 16 KB-page device mmap them. Without it the libs are only 4 KB-
    // aligned in the zip and fail to load on Android 15 16 KB devices even when their
    // own ELF segments are 16 KB-aligned.
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.spectrafilm.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 13
        versionName = "0.11.0"
        testInstrumentationRunner = "com.spectrafilm.app.ReleaseCandidateSmokeInstrumentation"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    signingConfigs {
        // Pin DEBUG signing to a committed keystore (standard public debug creds) so every
        // build — CI, any cloud container, every contributor — signs with ONE identical key,
        // and a freshly built debug APK always installs OVER a previous one. AGP otherwise
        // auto-generates a random ~/.android/debug.keystore per machine, whose differing
        // signature makes Android reject the update ("App not installed"). Not a release key.
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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
        debug {
            // en-XA / ar-XB for the #181 layout checks (expanded text, RTL).
            isPseudoLocalesEnabled = true
        }
        release {
            isMinifyEnabled = true
            // #462: drop resources nothing references (unused library drawables/strings).
            // Default "safe" mode keeps anything whose name appears as a string in code;
            // res/raw/keep.xml pins the names the release instrumentation APK resolves
            // with getIdentifier (Ticket181AccessibilityChecks), which R8 cannot see.
            isShrinkResources = true
            ndk {
                // Retain complete native symbols for the exact shipping build.
                // release.yml hash-binds and publishes AGP's symbols ZIP.
                debugSymbolLevel = "FULL"
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Local maintainers may opt in to a release key. CI intentionally leaves
            // this unset and transfers the unsigned, minified candidate to the
            // protected signing job.
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    // Per-app language (Android 13+): AGP writes the LocaleConfig from the values-* folders,
    // so a translation shows up in the system's per-app language list with no code change.
    // The default (unqualified) locale is declared in src/main/res/resources.properties.
    // localeFilters keeps the list to languages the APP translates: without it AGP would also
    // pick up AndroidX/Compose library translations (values-fr etc.) and offer languages in
    // which our own UI stays English. Add a locale here when its values-<locale> folder lands.
    // en-rXA / ar-rXB are the debug pseudolocales (isPseudoLocalesEnabled below): a locale
    // filter strips them too unless they are listed, which silently broke the #181 layout
    // checks. Release builds generate no pseudolocale resources, so listing them is inert there.
    androidResources {
        generateLocaleConfig = true
        localeFilters += listOf(
            "en", "de", "es", "fr", "ja", "ko", "pt-rBR", "zh-rCN",
            "en-rXA", "ar-rXB",
        )
    }
    // The gate APK must target the minified release variant that is later
    // externally signed, not AGP's default debug app.
    testBuildType = "release"
    // Robolectric Compose UI tests (#280) read real string resources.
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Screenshot tests of the shared UI (MAD audit fix 5), through AGP test suites
        // (AGP 9.5+; the standalone com.android.compose.screenshot plugin is unmaintained).
        // Sources: src/screenshotTest/kotlin. References: src/screenshotTestDefaultDebug/reference.
        // Update: ./gradlew :app:updateScreenshotTestDefaultDebugTestSuite
        // Check:  ./gradlew :app:testScreenshotTestDefaultDebugTestSuite
        screenshotTests.create("screenshotTest") {
            engineVersion = libs.versions.screenshotEngine.get()
            targetVariants.add("debug")
            dependencies {
                implementation(libs.androidx.ui.tooling)
                implementation(libs.screenshot.validation.api)
            }
        }
    }

    lint {
        // A translation may trail English by a release: a missing string falls back to
        // English at runtime, which is fine. TranslationKeysTest still blocks stale keys.
        warning += "MissingTranslation"
        // supportsRtl is on and the app ships translations: any future XML layout must mirror
        // too. Compose layouts are held to the same rule by LocalizedLayoutTest.
        error += setOf("RtlEnabled", "RtlHardcoded", "RtlCompat", "RtlSymmetry")
        // No baseline (#298): every entry the old one held had gone stale (AGP 8.7.3,
        // targetSdk 34, a 2024 Compose BOM), so it suppressed nothing current. New
        // findings now surface instead of being able to hide behind a baseline match.
        abortOnError = true
        checkReleaseBuilds = true
    }
}

// Baseline Profile consumer (#459). Profiles are recorded on a device by
// `./gradlew :app:generateBaselineProfile` and committed under
// src/main/generated/baselineProfiles/; a normal build never needs a device.
baselineProfile {
    mergeIntoMain = true
    automaticGenerationDuringBuild = false
    // Use the recorded startup profile to lay out the startup classes in the primary DEX.
    dexLayoutOptimization = true
}

// The plugin derives `nonMinifiedRelease` (profile generation) and `benchmarkRelease`
// (macrobenchmarks) from `release`, which carries no signing config outside the protected
// release job. Sign only those two device-test variants with the committed public debug key
// so they install; `release` itself stays unsigned.
androidComponents {
    // The plugin turns unit tests off for those two variants, but AGP's Compose Preview
    // (Layoutlib) tasks are registered for every variant and need them on to package
    // resources: without this, realizing all tasks (`./gradlew :app:tasks --all`) fails
    // with "Compose Preview failed for ':app' (variant 'nonMinifiedRelease')".
    beforeVariants(selector().withBuildType("nonMinifiedRelease")) { variant ->
        variant.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
    }
    beforeVariants(selector().withBuildType("benchmarkRelease")) { variant ->
        variant.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
    }
    onVariants(selector().withBuildType("nonMinifiedRelease")) { variant ->
        variant.signingConfig.setConfig(android.signingConfigs.getByName("debug"))
    }
    onVariants(selector().withBuildType("benchmarkRelease")) { variant ->
        variant.signingConfig.setConfig(android.signingConfigs.getByName("debug"))
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(project(":engine:spektra-core"))
    implementation(project(":lib:libraw"))
    implementation(project(":lib:tiffwriter"))
    implementation(project(":lib:pngwriter"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.exifinterface)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    // Renders @Preview in Android Studio; debug only, never in the release APK.
    debugImplementation(libs.androidx.ui.tooling)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.window)
    implementation(libs.androidx.core.splashscreen)
    // Installs the recorded Baseline Profile on sideloaded installs too (GitHub APK, no Play).
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))
    // On-device skin finder for AI masks (CPU only; the model is downloaded on first use, not bundled).
    implementation(libs.litert)

    testImplementation(libs.junit)
    // Real org.json on the unit-test classpath (the android.jar stub throws "not
    // mocked"); lets Presets JSON round-trip be tested on the plain JVM.
    testImplementation(libs.org.json)
    // Compose UI tests on the JVM under Robolectric (#280). The test manifest supplies the
    // ComponentActivity that createComposeRule launches; it is test-only, never in the APK.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    testImplementation(libs.androidx.ui.test.manifest)
    testImplementation(libs.robolectric)
}

// Canonical provenance input: Gradle's `dependencies` console report includes
// timing/task footers, so its bytes are not reproducible across identical runs.
val releaseRuntimeClasspath = providers.provider {
    configurations.getByName("releaseRuntimeClasspath")
}
val releaseRuntimeReport = rootProject.layout.buildDirectory.file(
    "release-runtime-classpath.txt"
)
tasks.register("writeReleaseRuntimeClasspath") {
    outputs.file(releaseRuntimeReport)
    outputs.upToDateWhen { false }
    doLast {
        val components = releaseRuntimeClasspath.get()
            .incoming.resolutionResult.allComponents
            .map { component ->
                when (val id = component.id) {
                    is ModuleComponentIdentifier ->
                        "module\t${id.group}\t${id.module}\t${id.version}"
                    is ProjectComponentIdentifier -> "project\t${id.projectPath}"
                    else -> "component\t${id.displayName}"
                }
            }
            .toSortedSet()
        releaseRuntimeReport.get().asFile.apply {
            parentFile.mkdirs()
            writeText(components.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        }
    }
}

// Print the assertion message of a failing JVM test in the build log (CI artifacts are not
// always reachable, and "AssertionError at Foo.kt:61" alone says nothing).
tasks.withType<Test>().configureEach {
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = false
    }
}
