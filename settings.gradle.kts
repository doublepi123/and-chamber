pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // LibADB Android 3.1.1 and spake2-android 2.2.1 are published through JitPack.
        // See third_party/phone-adb/README.md for provenance and license notes.
        maven("https://jitpack.io")
    }
}

rootProject.name = "AndChamber"
include(":app")
