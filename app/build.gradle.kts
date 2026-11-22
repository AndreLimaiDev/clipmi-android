import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Hasil scripts/build-native.sh: library statis libass dkk. (prefix/) dan executable qjs/ffmpeg (jniLibs/).
val nativeOut = rootProject.file("native/out/arm64-v8a")

// Kunci tanda tangan rilis tim (dibuat sekali dengan scripts/make-keystore.ps1; tidak pernah di-commit). Tanpa file ini
// APK rilis tidak ditandatangani.
val signing = rootProject.file("signing.properties").takeIf { it.isFile }?.let { file -> Properties().apply { file.inputStream().use { load(it) } } }

// Repo GitHub rilis APK ("pemilik/nama") untuk pembaruan dalam aplikasi, misalnya: -Pclipmi.updateRepo=tim/clipmi-android
// atau di gradle.properties. Kosong = pemeriksaan pembaruan tidak aktif.
val updateRepo = providers.gradleProperty("clipmi.updateRepo").getOrElse("")

android {
    // Model LiteRT dibaca langsung dari APK.
    androidResources { noCompress += "tflite" }
    namespace = "id.clipmi.android"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "id.clipmi.android"
        minSdk = 29
        targetSdk = 37
        versionCode = providers.gradleProperty("clipmi.versionCode").getOrElse("1").toInt()
        versionName = providers.gradleProperty("clipmi.versionName").getOrElse("0.0.1")
        ndk { abiFilters += listOf("arm64-v8a") }
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")

        externalNativeBuild {
            cmake {
                // c++_static: HarfBuzz ditulis dalam C++, tetapi libc++_shared.so tidak perlu ikut dibundel.
                arguments += listOf(
                    "-DCLIPMI_NATIVE_PREFIX=${nativeOut.resolve("prefix").absolutePath.replace('\\', '/')}",
                    "-DANDROID_STL=c++_static",
                )
            }
        }
    }

    signingConfigs {
        if (signing != null) {
            create("release") {
                storeFile = rootProject.file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    lint {
        // Salah positif: sebuah library mendeklarasikan fragment 1.1.0, padahal yang terpakai 1.3.6 (≥ 1.3.0), dan
        // MainActivity adalah ComponentActivity, bukan FragmentActivity.
        disable += "InvalidFragmentVersionForActivityResult"
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Calon rilis: sama dengan rilis (R8, shrink) tetapi bertanda tangan debug dan berpaket lain (".rc"), jadi bisa
        // dipasang di samping aplikasi yang ada untuk menguji hasil R8 tanpa menyentuh data.
        create("rc") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".rc"
            versionNameSuffix = "-rc"
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    sourceSets {
        getByName("main") {
            // libqjs.so dan libffmpeg.so adalah executable; Android hanya mengizinkan menjalankan file dari
            // nativeLibraryDir, jadi keduanya dibundel sebagai "library" dan diekstrak saat dipasang.
            jniLibs.directories.add(nativeOut.resolve("jniLibs").absolutePath)
        }
    }

    packaging {
        // youtubedl-android dan executable di atas harus diekstrak ke disk (extractNativeLibs).
        jniLibs {
            useLegacyPackaging = true
            // Arsip Python (zip berkedok .so), bukan ELF: jangan dicoba di-strip.
            keepDebugSymbols += "**/libpython.zip.so"
            // libqjs.so: QuickJS-ng 0.18.0 buatan sendiri (native/out) menggantikan QuickJS 2025-04-26 bawaan
            // youtubedl-android; tantangan JS YouTube 25% lebih cepat di HP uji (15,5 vs 20,9 detik).
            pickFirsts += "**/libqjs.so"
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":shared"))
    implementation(project(":ui"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.work.runtime)

    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.muxer)
    implementation(libs.media3.ui)
    implementation(libs.okhttp)
    // Deteksi wajah "ikuti penceramah" di HP (CPU, tanpa layanan Google).
    implementation(libs.litert)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.youtubedl.library)

    testImplementation(libs.junit)
}
