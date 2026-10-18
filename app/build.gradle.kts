// AndChamber app module. Native PRoot pipeline ported from AndCode (MIT); see NOTICE.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val repoRoot = rootProject.projectDir
val generatedRuntimeAssets = rootProject.layout.buildDirectory.dir("generated/runtime-assets")
val generatedRuntimeJni = rootProject.layout.buildDirectory.dir("generated/runtime-jni")

// Pinned Termux packages (proot, talloc, android-shmem) are fetched from the lock file and
// unpacked; prepareAndChamberRuntimeNativeLibs then renames the executables into jniLibs.
val prepareAndChamberRuntimeAssets =
    tasks.register<Exec>("prepareAndChamberRuntimeAssets") {
        inputs.file(repoRoot.resolve("runtime_tools/termux_assets.py"))
        inputs.file(repoRoot.resolve("runtime_tools/termux_assets.lock.json"))
        inputs.file(repoRoot.resolve("scripts/prepare_android_runtime_assets.py"))
        outputs.dir(generatedRuntimeAssets)
        commandLine(
            "python3",
            repoRoot.resolve("scripts/prepare_android_runtime_assets.py").absolutePath,
            "--output-dir",
            generatedRuntimeAssets.get().asFile.absolutePath,
            "--lock-file",
            repoRoot.resolve("runtime_tools/termux_assets.lock.json").absolutePath,
        )
    }

val prepareAndChamberRuntimeNativeLibs =
    tasks.register<Exec>("prepareAndChamberRuntimeNativeLibs") {
        dependsOn(prepareAndChamberRuntimeAssets)
        inputs.dir(generatedRuntimeAssets)
        inputs.file(repoRoot.resolve("scripts/prepare_android_runtime_native_libs.py"))
        outputs.dir(generatedRuntimeJni)
        commandLine(
            "python3",
            repoRoot.resolve("scripts/prepare_android_runtime_native_libs.py").absolutePath,
            "--linux-assets-dir",
            generatedRuntimeAssets.get().asFile.absolutePath,
            "--output-dir",
            generatedRuntimeJni.get().asFile.absolutePath,
        )
    }

android {
    namespace = "io.andchamber.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.andchamber.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // The on-device runtime only exists for these two ABIs (see prepare_android_runtime_native_libs.py).
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    }

    sourceSets {
        getByName("main").jniLibs.srcDir(generatedRuntimeJni)
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

tasks.named("preBuild").configure {
    dependsOn(prepareAndChamberRuntimeNativeLibs)
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
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

    implementation("io.insert-koin:koin-android:4.0.1")
    implementation("io.insert-koin:koin-androidx-compose:4.0.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
