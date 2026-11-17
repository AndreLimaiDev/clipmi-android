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
include(":app", ":core")
