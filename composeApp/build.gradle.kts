// Aplikasi iOS ClipMI (Compose Multiplatform). Dibangun di macOS (CI): framework statis ComposeApp dipakai proyek
// Xcode di iosApp/. Bagian khusus iOS yang ditulis di Swift (Python + yt-dlp, AVFoundation) masuk lewat MediaEngine.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    iosArm64 {
        binaries.framework {
            baseName = "ComposeApp"
            // Dinamis: framework membawa sendiri semua library sistem yang dipakai Compose (Metal, UIKit, dll.).
            isStatic = false
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":core"))
                implementation(libs.cmp.runtime)
                implementation(libs.cmp.foundation)
                implementation(libs.cmp.ui)
                implementation(libs.cmp.material3)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }
    }
}
