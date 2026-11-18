plugins {
    id("com.android.application")
    // No org.jetbrains.kotlin.android: AGP 9 bundles Kotlin support.
    id("org.jetbrains.kotlin.plugin.compose")
}

// Device: Samsung S22 (Snapdragon SM8450, arm64) on stock Android 16 —
// translation path, no /dev/kvm. minSdk 33 keeps the 16 KB page-size mandate
// (targetSdk 35+) away from the JIT's raw mmap allocator; compileSdk 37 is
// the installed final-base platform. arm64-v8a only.
android {
    namespace = "com.crate.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.crate.app"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
    }
}

// Compose BOM 2026.09.00 (Maven, latest stable 2026-11-18); activity and
// lifecycle versions come from the BOM, no pins of our own.
dependencies {
    val bom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(bom)
    androidTestImplementation(bom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
}
