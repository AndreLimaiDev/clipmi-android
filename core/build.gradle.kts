import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Logika inti Kotlin murni (tanpa Android): padanan langsung kode desktop, dikunci dengan tes perbandingan fixture.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
