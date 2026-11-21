// The whole Kite app, shared by Android and iPhone: every screen, the map, navigation.
// Only what each phone does its own way (GPS, voice, the lock-screen turn...) is in androidMain/iosMain.
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

val withAndroid = !providers.gradleProperty("noAndroid").isPresent

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}
// Android is added only when building for Android, so the iPhone side can be built with no Android SDK.
if (withAndroid) apply(plugin = "com.android.kotlin.multiplatform.library")

kotlin {
    val xcf = XCFramework("KiteShared")
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "KiteShared"
            isStatic = false
            export(project(":core"))
            xcf.add(this)
        }
    }

    compilerOptions {
        optIn.addAll(
            "kotlin.uuid.ExperimentalUuidApi", "kotlin.time.ExperimentalTime",
            "kotlinx.cinterop.ExperimentalForeignApi", "kotlinx.cinterop.BetaInteropApi",
            "androidx.compose.material3.ExperimentalMaterial3Api", "androidx.compose.foundation.ExperimentalFoundationApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi", "androidx.compose.ui.ExperimentalComposeUiApi",
            "org.jetbrains.compose.resources.ExperimentalResourceApi",
        )
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    if (withAndroid) {
        (this as ExtensionAware).extensions.configure<com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget>("android") {
            namespace = "app.kite.shared"
            compileSdk = 37
            minSdk = 26
            androidResources { enable = true }
            compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
        }
        sourceSets.getByName("androidMain").dependencies {
            implementation("androidx.core:core-ktx:1.17.0")
            implementation("androidx.activity:activity-compose:1.12.0")
            implementation("androidx.webkit:webkit:1.17.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
            // The map's drawing engine for Android (the iPhone's Metal one comes with maplibre-compose).
            // OpenGL, like Kite always used: it works on every phone and the emulator.
            implementation("org.maplibre.nativeffi:maplibre-native-ffi-runtime-opengl:0.202610.0")
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            implementation("org.jetbrains.compose.runtime:runtime:1.12.2")
            implementation("org.jetbrains.compose.foundation:foundation:1.12.2")
            implementation("org.jetbrains.compose.ui:ui:1.12.2")
            implementation("org.jetbrains.compose.animation:animation:1.12.2")
            implementation("org.jetbrains.compose.material3:material3:1.9.0")
            implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
            implementation("org.jetbrains.compose.components:components-resources:1.12.2")
            implementation("org.jetbrains.compose.ui:ui-backhandler:1.12.2")
            implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
            implementation("org.maplibre.compose:maplibre-compose:0.20.0")
            implementation("io.coil-kt.coil3:coil-compose:3.6.3")
            implementation("io.coil-kt.coil3:coil-network-ktor3:3.6.3")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
        }
    }
}

compose.resources {
    publicResClass = false
    packageOfResClass = "app.kite.resources"
    generateResClass = always
}
