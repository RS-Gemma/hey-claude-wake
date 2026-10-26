plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pistolwhip.wakephraseservice"
    compileSdk = 35

    defaultConfig {
        applicationId = "it.aicdp.heyclaude"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "2.0.0"
        testInstrumentationRunner = "android.test.runner.AndroidJUnitRunner"
    }

    // Fixed debug key so each CI build installs as an update over the previous one.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.github.msnilsen:openwakeword-android:0.1.0")
}
