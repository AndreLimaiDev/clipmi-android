import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Logika inti Kotlin (tanpa Android): padanan langsung kode desktop, dikunci dengan tes perbandingan fixture.
// Multiplatform: jvm (dipakai aplikasi Android) dan iOS. Target iOS hanya dibangun di macOS (CI) atau bila
// -Pclipmi.ios diberikan, supaya build di Windows tidak mengunduh compiler Kotlin/Native.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

val iosEnabled = System.getProperty("os.name").startsWith("Mac") || providers.gradleProperty("clipmi.ios").isPresent

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    if (iosEnabled) {
        iosArm64()
        iosSimulatorArm64()
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlinx.serialization.json)
                api(libs.kotlinx.datetime)
            }
        }
        commonTest {
            // Lokasi fixture (core/fixtures) sebagai konstanta: tes JVM dan simulator iOS membacanya dari disk.
            kotlin.srcDir(layout.buildDirectory.dir("generated/fixtures"))
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

val fixturePath = tasks.register("fixturePath") {
    val dir = layout.projectDirectory.dir("fixtures").asFile.absolutePath.replace('\\', '/')
    val out = layout.buildDirectory.file("generated/fixtures/id/clipmi/core/FixtureDir.kt")
    inputs.property("dir", dir)
    outputs.file(out)
    doLast {
        out.get().asFile.apply { parentFile.mkdirs() }.writeText(
            "package id.clipmi.core\n\n/** Dibuat Gradle (tugas fixturePath). */\nconst val FIXTURE_DIR = \"$dir\"\n",
        )
    }
}

tasks.matching { it.name.startsWith("compileTestKotlin") || it.name.matches(Regex("compile.*TestKotlin.*")) }.configureEach {
    dependsOn(fixturePath)
}
