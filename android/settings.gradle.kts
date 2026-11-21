// Kite Maps: ONE app for Android and iPhone (Kotlin Multiplatform + Compose Multiplatform).
// `:core` is the engine (search, routing, cameras, Waze...), no screens, tests run anywhere;
// `:shared` is the whole app (every screen, the map, driving), the same code on both phones;
// `:app` is the thin Android shell around it. The iPhone shell is in ../ios.
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
        id("org.jetbrains.compose") version "1.12.2"
        id("org.jetbrains.kotlin.jvm") version "2.4.20"
        id("org.jetbrains.kotlin.multiplatform") version "2.4.20"
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
// `-PnoAndroid` keeps the shared app but leaves Android out (checks the shared code without an Android SDK).
if (!providers.gradleProperty("coreOnly").isPresent) {
    include(":shared")
    if (!providers.gradleProperty("noAndroid").isPresent) include(":app")
}
