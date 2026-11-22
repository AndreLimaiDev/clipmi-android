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

// app: aplikasi Android. core: logika Kotlin murni (padanan kode desktop), bisa diuji tanpa HP.
// -Pclipmi.coreOnly (CI tes iOS di macOS): hanya core, supaya tidak perlu Android SDK.
if (providers.gradleProperty("clipmi.coreOnly").isPresent) include(":core") else include(":app", ":core")
