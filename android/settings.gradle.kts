// Kite Maps for Android. Two modules, like the iPhone app's VelaCore + App:
// `:core` is plain Kotlin (search, routing, cameras, Waze...), no Android in it, so its tests run anywhere;
// `:app` is the Android app on top (Compose screens and the MapLibre map).
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "9.4.1"
        id("org.jetbrains.kotlin.jvm") version "2.4.20"
        id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Kite"

include(":core")
// `-PcoreOnly` skips the app (a machine with no Android SDK can still run the core tests).
if (!providers.gradleProperty("coreOnly").isPresent) include(":app")
