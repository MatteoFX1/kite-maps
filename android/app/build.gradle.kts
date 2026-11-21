import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The same version numbers as the iPhone app. CI can pass -PappVersionName=0.7.6.
val kiteVersion = (project.findProperty("appVersionName") as String?) ?: "0.8.0"
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
    // The whole app (screens, map, driving) is the shared code, the same as the iPhone's.
    implementation(project(":shared"))
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.0")
}
