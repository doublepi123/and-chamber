import java.io.File
import java.util.Properties

// AndChamber app module. Native PRoot pipeline ported from AndCode (MIT); see NOTICE.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val repoRoot = rootProject.projectDir
val generatedRuntimeJni = rootProject.layout.buildDirectory.dir("generated/runtime-jni")
val runtimeNativeWorkDir = rootProject.layout.buildDirectory.dir("runtime-native")
val nativeLicenseSourceDir = repoRoot.resolve("third_party/native-runtime")
val generatedNativeLicenseAssets = rootProject.layout.buildDirectory.dir("generated/native-license-assets")

// The runtime native sources are NOT downloaded by the build. They are prepared beforehand with the
// network step (see docs/fdroid-source-build.md):
//   python3 scripts/prepare_android_runtime_sources.py --output-dir build/runtime-sources
// The Gradle build then compiles them offline with the pinned NDK and emits the JNI libraries.
// Relative override paths resolve against the project root (not the Gradle invocation directory).
val runtimeSourcesDir: File =
    providers.gradleProperty("andchamber.runtimeSourcesDir").orNull
        ?.let { repoRoot.resolve(it) }
        ?: rootProject.layout.buildDirectory.get().asFile.resolve("runtime-sources")
val runtimeSourcesLock = repoRoot.resolve("runtime_tools/runtime_sources.lock.json")
val runtimeNativeScript = repoRoot.resolve("scripts/build_android_runtime_native.py")
val runtimeSourcesScript = repoRoot.resolve("scripts/prepare_android_runtime_sources.py")
val runtimePrepareCommand =
    "python3 scripts/prepare_android_runtime_sources.py --output-dir ${runtimeSourcesDir.absolutePath}"

val ndkVersionPinned = "29.0.14206865"

// Explicit file lists (no globs), so build/ outputs and __pycache__ never enter the fingerprint.
val runtimePatchFiles: FileCollection =
    files(
        repoRoot.resolve("runtime_tools/patches/libandroid-shmem-0.7-andchamber-tmpdir.patch"),
        repoRoot.resolve("runtime_tools/patches/proot-5.1.107.97-andchamber-string-include.patch"),
        repoRoot.resolve("runtime_tools/patches/loader-info-posix-awk.awk"),
    )

// SDK root resolved from the same sources AGP uses: explicit property, ANDROID_HOME/ANDROID_SDK_ROOT, local.properties.
val androidSdkDir: File? =
    (providers.gradleProperty("andchamber.sdkDir").orNull
        ?: System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: rootProject.file("local.properties").takeIf { it.isFile }?.let { f ->
            Properties().apply { f.inputStream().use { load(it) } }.getProperty("sdk.dir")
        })?.let { repoRoot.resolve(it) }
val ndkDirectory: File =
    providers.gradleProperty("andchamber.ndkDir").orNull?.let { repoRoot.resolve(it) }
        ?: androidSdkDir?.resolve("ndk/$ndkVersionPinned")
        ?: File("/nonexistent/ndk/$ndkVersionPinned")

// Compiles the runtime from the prepared sources. Offline by design: no network access happens here.
// Inputs: the lock, the build/prepare scripts, the runtime_tools Python modules, the prepared source tree and the
// NDK version. The script works in its own copies under build/runtime-native, so the source tree is never mutated
// and is not an output. The doFirst checks run only when the task executes, so configuration and ./gradlew help
// never compile native code.
val buildAndChamberRuntimeNative =
    tasks.register<Exec>("buildAndChamberRuntimeNative") {
        // Every input that changes the native output. Patches and the awk helper are part of the build.
        inputs.file(runtimeSourcesLock).withPropertyName("runtimeSourcesLock")
        inputs.file(runtimeNativeScript).withPropertyName("buildScript")
        inputs.file(runtimeSourcesScript).withPropertyName("prepareScript")
        inputs.files(runtimePatchFiles).withPropertyName("runtimePatches")
        // NDK identity: source.properties carries the revision; the directory itself can be any path.
        inputs.file(ndkDirectory.resolve("source.properties")).withPropertyName("ndkSourceProperties").optional(true)
        inputs.property("ndkVersion", ndkVersionPinned)
        inputs.dir(runtimeSourcesDir).withPropertyName("runtimeSourceTree").optional(true)
        outputs.dir(generatedRuntimeJni)

        doFirst {
            if (!runtimeSourcesDir.isDirectory) {
                throw GradleException(
                    "Runtime sources not found at ${runtimeSourcesDir.absolutePath}.\n" +
                        "Prepare them first (network step, not run by Gradle):\n  $runtimePrepareCommand",
                )
            }
            if (!ndkDirectory.isDirectory) {
                throw GradleException(
                    "NDK $ndkVersionPinned not found at ${ndkDirectory.absolutePath}.\n" +
                        "Install it with: sdkmanager \"ndk;$ndkVersionPinned\" (or pass -Pandchamber.ndkDir=<path>).",
                )
            }
            // Sources must stay read-only inputs: the build works under runtime-native and writes only jniLibs output.
            val sources = runtimeSourcesDir.canonicalFile
            val work = runtimeNativeWorkDir.get().asFile.canonicalFile
            val out = generatedRuntimeJni.get().asFile.canonicalFile
            if (sources == work || sources.startsWith(work) || work.startsWith(sources) ||
                sources == out || out.startsWith(sources) || sources.startsWith(out)
            ) {
                throw GradleException(
                    "runtimeSourcesDir ($sources) must not overlap the native work dir ($work) or output dir ($out).",
                )
            }
        }

        commandLine(
            "python3",
            runtimeNativeScript.absolutePath,
            "--sources-dir",
            runtimeSourcesDir.absolutePath,
            "--ndk-dir",
            ndkDirectory.absolutePath,
            "--output-dir",
            generatedRuntimeJni.get().asFile.absolutePath,
            "--work-dir",
            runtimeNativeWorkDir.get().asFile.absolutePath,
        )
    }

// Debug-only opt-in application id for installing next to the production app on an emulator.
// Example: ./gradlew :app:assembleDebug -Pandchamber.testApplicationId=io.andchamber.fdroidtest
val productionApplicationId = "io.andchamber.app"
val testApplicationId: String? =
    providers.gradleProperty("andchamber.testApplicationId").orNull?.takeIf { it.isNotBlank() }
val appIdRegex = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")
val resolvedApplicationId: String =
    testApplicationId?.also { id ->
        require(id != productionApplicationId) {
            "andchamber.testApplicationId must not equal the production id $productionApplicationId"
        }
        require(appIdRegex.matches(id) && id.split('.').size >= 3 && !id.startsWith("android.")) {
            "andchamber.testApplicationId '$id' is not a valid Android application id"
        }
    } ?: productionApplicationId
val isTestIdRequested = testApplicationId != null
val providerArtifactDir: java.io.File? =
    providers.gradleProperty("andchamber.providerArtifactDir").orNull
        ?.takeIf { it.isNotBlank() }
        ?.let { File(it) }
val browserSmokeRunnerRequested = providers.gradleProperty("andchamber.browserSmokeRunner").orNull
val useBrowserSmokeRunner = when (browserSmokeRunnerRequested) {
    null, "false" -> false
    "true" -> true
    else -> error("andchamber.browserSmokeRunner must be true or false")
}

// Version: CI passes the tag-derived values as Gradle properties; local builds use the defaults.
val appVersionName: String = providers.gradleProperty("andchamber.versionName").orNull ?: "0.0.1"
val appVersionCode: Int = providers.gradleProperty("andchamber.versionCode").orNull?.toInt() ?: 1

// Release signing is enabled only when all four values are provided (env var or Gradle property of
// the same lowercase name). Without them release builds stay unsigned, so local builds still work.
fun signingValue(name: String): String? =
    (System.getenv(name) ?: providers.gradleProperty(name.lowercase()).orNull)?.takeIf { it.isNotBlank() }

val releaseKeystoreFile = signingValue("ANDCHAMBER_KEYSTORE_FILE")
val releaseKeystorePassword = signingValue("ANDCHAMBER_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("ANDCHAMBER_KEY_ALIAS")
val releaseKeyPassword = signingValue("ANDCHAMBER_KEY_PASSWORD")
val hasReleaseSigning =
    listOf(releaseKeystoreFile, releaseKeystorePassword, releaseKeyAlias, releaseKeyPassword).all { it != null }

android {
    namespace = "io.andchamber.app"
    compileSdk = 35
    ndkVersion = ndkVersionPinned

    defaultConfig {
        // Production id. Only a debug build can override it, through andchamber.testApplicationId (see below).
        applicationId = productionApplicationId
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = when {
            providers.gradleProperty("andchamber.providerRunner").orNull == "true" ->
                "io.andchamber.app.browser.OpenChamberProviderInstrumentation"
            useBrowserSmokeRunner ->
                "io.andchamber.app.browser.BrowserSmokeInstrumentation"
            else ->
                "androidx.test.runner.AndroidJUnitRunner"
        }

        // The on-device runtime only exists for these two ABIs (see prepare_android_runtime_native_libs.py).
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        // Only the shipped languages are packaged; third-party AARs bring many more locale folders.
        resourceConfigurations += listOf("en", "zh")

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystoreFile!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // v1 is kept for old verifiers; v2/v3 are what Android installs with.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    sourceSets {
        if (providerArtifactDir != null) {
            // Debug/emulator provisioning only. Release must not embed the patched server+dist pair.
            check(providerArtifactDir.isDirectory) { "andchamber.providerArtifactDir is not a directory" }
            getByName("debug").assets.srcDir(File(providerArtifactDir, "apk-asset"))
        }
    }

    buildTypes {
        release {
            // No R8 in v0.0.1: shrinking is a separate risk to validate on device first.
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    testOptions {
        unitTests {
            // android.util.Log and android.* stubs return defaults instead of throwing.
            isReturnDefaultValues = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main").jniLibs.srcDir(generatedRuntimeJni)
        getByName("main").assets.srcDir(generatedNativeLicenseAssets)
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    androidResources {
        noCompress += "tflite"
    }
}

// Native license texts and provenance are copied from the single source of truth in
// third_party/native-runtime/ (declared near the top of this script) into a generated assets folder,
// packaged in every variant. The copy declares inputs and outputs so it is up to date when unchanged.
val syncNativeLicenseAssets =
    tasks.register<Sync>("syncNativeLicenseAssets") {
        from(nativeLicenseSourceDir) {
            include("**/*")
            into("licenses/native")
        }
        into(generatedNativeLicenseAssets)
        inputs.dir(nativeLicenseSourceDir).withPropertyName("nativeLicenseSources")
        outputs.dir(generatedNativeLicenseAssets).withPropertyName("nativeLicenseAssets")
    }

tasks.named("preBuild").configure {
    dependsOn(buildAndChamberRuntimeNative)
    dependsOn(syncNativeLicenseAssets)
}

// Release APK is named AndChamber-<versionName>.apk for publishing. Debug keeps the AGP default.
// AGP 8.5 exposes the output file name on the variant's ApkVariantOutput (the applicationVariants API).
// The debug test id is applied through the variant API (AGP 8.5 androidComponents), so release always keeps
// the production id.
androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        if (isTestIdRequested) {
            variant.applicationId.set(resolvedApplicationId)
        }
    }
}

android.applicationVariants.all {
    if (buildType.name == "release") {
        outputs.all {
            (this as com.android.build.gradle.internal.api.ApkVariantOutputImpl).outputFileName =
                "AndChamber-$appVersionName.apk"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    // Process-wide foreground state, used to suppress OpenChamber notifications while the app is open.
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.9")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.webkit:webkit:1.14.0")

    implementation("io.insert-koin:koin-android:4.0.1")
    implementation("io.insert-koin:koin-androidx-compose:4.0.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
