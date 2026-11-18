// Top-level build file: plugin versions only. The app's own versions live in
// app/build.gradle.kts. All three versions below were read off Maven on
// 2026-11-18 (latest stable): AGP from
// dl.google.com/dl/android/maven2/com/android/tools/build/gradle, KGP from
// repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin,
// Compose BOM from dl.google.com/dl/android/maven2/androidx/compose/compose-bom.
plugins {
    id("com.android.application") version "9.5.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
}
