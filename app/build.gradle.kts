plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val build = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.darfsenaster.mccommandlab"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.darfsenaster.mccommandlab"
        minSdk = 26
        targetSdk = 34
        versionCode = build
        versionName = "1.$build"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("mc.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
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
    implementation("androidx.core:core-ktx:1.13.1")
}
