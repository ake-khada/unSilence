plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace  = "com.unsilence.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.unsilence.app"
        minSdk        = 26
        targetSdk     = 36
        versionCode   = 1
        versionName   = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)

    // Activity / Navigation / Lifecycle
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")

    // Networking
    implementation(libs.okhttp)

    // Serialization / Coroutines
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Hilt (DI)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    ksp("org.jetbrains.kotlin:kotlin-metadata-jvm:2.3.0")
    implementation(libs.hilt.navigation.compose)

    // Room (local cache — UI reads only from Room)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)

    // Coil (image loading, GIF, blurhash)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.network.okhttp)

    // Media (ExoPlayer — kind 21 tap-to-play only in v1)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    // Other
    implementation(libs.security.crypto)    // NWC key storage (Android Keystore)
    implementation(libs.splashscreen)
    implementation(libs.zxing.core)         // QR scanner for NWC setup (Sprint 4)

    // Nostr protocol (event parsing, signing, NIP implementations)
    implementation(libs.quartz.android)

    // Pubkey-derived identicons (Maven Central)
    implementation(libs.identikon.android)
}
