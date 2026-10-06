plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseVersionCode = providers.gradleProperty("VERSION_CODE")
    .orNull?.toIntOrNull()?.takeIf { it > 0 } ?: 1
val signingStorePath = System.getenv("ANDROID_KEYSTORE_PATH")

android {
    namespace = "il.co.maqshim.launcher"
    compileSdk = 35
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    defaultConfig {
        applicationId = "il.co.maqshim.launcher"
        minSdk = 26
        targetSdk = 35
        versionCode = releaseVersionCode
        versionName = "1.0.$releaseVersionCode"
    }
    signingConfigs {
        create("release") {
            if (!signingStorePath.isNullOrBlank()) {
                storeFile = file(signingStorePath)
                storeType = "PKCS12"
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            if (!signingStorePath.isNullOrBlank()) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }
}
