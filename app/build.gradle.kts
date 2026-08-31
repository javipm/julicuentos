// Julicuentos — audiobook player for Android 5.1.1 (Fire HD 10 2015, API 22).
// Constraints (design.md D1/D9): no Compose, no Material Components, no DI,
// no coroutines, no core library desugaring.

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.julicuentos.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.julicuentos.app"
        minSdk = 22
        targetSdk = 34
        versionCode =  2
        versionName = "1.0"
    }

    buildTypes {
        release {
            // Debug-only delivery for now; no R8 so stack traces stay legible on device.
            isMinifyEnabled = false
        }
    }

    androidResources {
        // Keep MP3 entries STORED (mmap-backed, O(1) AssetInputStream.seekTo on API 22).
        noCompress += "mp3"
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
    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.media)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    testImplementation(libs.junit)
}