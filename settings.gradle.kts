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
    }
}

rootProject.name = "clipmi-android"

// app: aplikasi Android. shared: database Room bersama (Android + iOS). core: logika Kotlin (padanan kode desktop, juga untuk iOS). composeApp: aplikasi iOS
// (Compose Multiplatform), hanya di macOS atau dengan -Pclipmi.ios.
// -Pclipmi.coreOnly (CI iOS di macOS): tanpa app, supaya tidak perlu Android SDK.
include(":core")
include(":shared")
if (!providers.gradleProperty("clipmi.coreOnly").isPresent) include(":app")
if (System.getProperty("os.name").startsWith("Mac") || providers.gradleProperty("clipmi.ios").isPresent) include(":composeApp")
