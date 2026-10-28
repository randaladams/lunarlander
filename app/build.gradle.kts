plugins {
    id("com.android.application")
}

android {
    namespace = "com.adamselite.lunarlander"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.adamselite.lunarlander"
        minSdk = 26          // Android 8.0+
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-draft"
    }

    signingConfigs {
        // A debug key checked into the repo, so every build (yours or CI's)
        // has the same signature and installs over the previous one.
        // Not for Play Store release.
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
