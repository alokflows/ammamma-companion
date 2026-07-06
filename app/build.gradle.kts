import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing secrets live in keystore.properties (gitignored), NEVER in this file.
// Without it (e.g. a fresh clone), the release build simply isn't signed.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) load(FileInputStream(keystorePropsFile))
}

android {
    namespace = "com.ammamma.companion"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ammamma.companion"
        minSdk = 26
        // Target her phone's own API level so Android applies the old, simpler
        // background/permission rules the device actually expects.
        targetSdk = 27
        versionCode = 1
        versionName = "0.1"
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // We sideload to Android 8.1 on purpose, so targetSdk 27 is correct — this
        // check is a Google Play requirement that doesn't apply to us.
        disable += "ExpiredTargetSdkVersion"
        abortOnError = false
    }
}

dependencies {
    // Intentionally empty for the foundation build: pure Android framework + Kotlin
    // stdlib. Fewer moving parts = fewer things that can break on Android 8.
}
