// Data bersama Android dan iOS: entity, DAO, dan @Database Room (KMP). Paket tetap id.clipmi.android.data supaya
// kode aplikasi Android tidak berubah. Migrasi SupportSQLite (database lama di HP) dan pembuatan database Android
// tetap di :app; iOS memakai BundledSQLiteDriver dan langsung mulai dari skema terbaru.
// Target Android dilewati dengan -Pclipmi.coreOnly (CI iOS di macOS tanpa Android SDK); target iOS hanya di macOS
// atau dengan -Pclipmi.ios, sama dengan :core.
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

val iosEnabled = System.getProperty("os.name").startsWith("Mac") || providers.gradleProperty("clipmi.ios").isPresent
val androidEnabled = !providers.gradleProperty("clipmi.coreOnly").isPresent

if (androidEnabled) {
    apply(plugin = "com.android.kotlin.multiplatform.library")
    kotlin.extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("android") {
        namespace = "id.clipmi.shared"
        compileSdk = 37
        minSdk = 29
    }
}

kotlin {
    if (iosEnabled) {
        iosArm64()
        iosSimulatorArm64()
    }

    sourceSets {
        commonMain {
            dependencies {
                api(libs.room.runtime)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        if (iosEnabled) {
            iosMain {
                dependencies {
                    implementation(libs.sqlite.bundled)
                }
            }
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    if (androidEnabled) add("kspAndroid", libs.room.compiler)
    if (iosEnabled) {
        add("kspIosArm64", libs.room.compiler)
        add("kspIosSimulatorArm64", libs.room.compiler)
    }
}
