import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The same version numbers as the iPhone app. CI can pass -PappVersionName=0.7.6.
val kiteVersion = (project.findProperty("appVersionName") as String?) ?: "0.7.8"
// 0.7.6 -> 706: always goes up with the version, so F-Droid sees every release as an update.
val kiteCode = kiteVersion.split(".").map { it.toIntOrNull() ?: 0 }.let { (it.getOrElse(0) { 0 } * 10000) + (it.getOrElse(1) { 0 } * 100) + it.getOrElse(2) { 0 } }

android {
    namespace = "app.kite"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.kite"
        minSdk = 26
        targetSdk = 35
        versionCode = kiteCode
        versionName = kiteVersion
        vectorDrawables { useSupportLibrary = true }
    }

    // Release signing comes from CI (KITE_KEYSTORE_PATH / KITE_KEYSTORE_PASSWORD). Without it the
    // APK is signed with the debug key, which still installs but can't update an F-Droid copy.
    signingConfigs {
        create("releaseFromEnv") {
            val path = System.getenv("KITE_KEYSTORE_PATH")
            if (!path.isNullOrBlank() && File(path).exists()) {
                storeFile = File(path)
                storePassword = System.getenv("KITE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KITE_KEY_ALIAS") ?: "kite"
                keyPassword = System.getenv("KITE_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val env = signingConfigs.getByName("releaseFromEnv")
            signingConfig = if (env.storeFile?.exists() == true) env else signingConfigs.getByName("debug")
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
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
    // F-Droid and reproducible builds: no Google dependency-info blob in the APK.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3:1.4.0-alpha06")
    implementation("androidx.webkit:webkit:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.maplibre.gl:android-sdk-opengl:13.6.1")
}
