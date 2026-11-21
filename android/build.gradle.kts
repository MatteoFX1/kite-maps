// The Android Gradle plugin is always on the classpath (the shared app's build names its classes),
// but only applied when building for Android, so `-PnoAndroid` needs no Android SDK.
buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        if (!project.hasProperty("coreOnly")) {
            classpath("com.android.tools.build:gradle:9.4.1")
        }
    }
}

// Loaded once here, so every module shares the same Kotlin and Compose plugins.
plugins {
    id("org.jetbrains.kotlin.multiplatform") apply false
    id("org.jetbrains.kotlin.plugin.compose") apply false
    id("org.jetbrains.compose") apply false
}
