// Tampilan bersama Android dan iOS: token warna, tipografi, ikon, dan komponen ClipMI (Compose Multiplatform). Paket
// tetap id.clipmi.android.ui.* supaya kode aplikasi Android tidak berubah. Font Plus Jakarta Sans dipasang tiap
// aplikasi (ClipMiFonts.install). Target Android dilewati dengan -Pclipmi.coreOnly; target iOS hanya di macOS atau
// dengan -Pclipmi.ios, sama dengan :shared.
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlin.compose)
}

val iosEnabled = System.getProperty("os.name").startsWith("Mac") || providers.gradleProperty("clipmi.ios").isPresent
val androidEnabled = !providers.gradleProperty("clipmi.coreOnly").isPresent

if (androidEnabled) {
    apply(plugin = "com.android.kotlin.multiplatform.library")
    kotlin.extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("android") {
        namespace = "id.clipmi.ui"
        compileSdk = 37
        minSdk = 29
    }
}

kotlin {
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }
    if (iosEnabled) {
        iosArm64()
        iosSimulatorArm64()
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":shared"))
                api(libs.cmp.runtime)
                api(libs.cmp.foundation)
                api(libs.cmp.ui)
                api(libs.cmp.material3)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        if (androidEnabled) {
            androidMain {
                dependencies {
                    // Android tetap memakai versi Compose dari BOM aplikasi (material3 stabil), bukan versi alpha yang
                    // dibawa Compose Multiplatform; modul ini juga dikompilasi terhadap versi yang sama.
                    implementation(project.dependencies.enforcedPlatform(libs.compose.bom))
                    implementation(libs.androidx.activity.compose)
                    implementation(libs.androidx.core.ktx)
                }
            }
        }
    }
}
