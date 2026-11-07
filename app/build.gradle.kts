plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "id.altair.musevpn"
    compileSdk = 35

    defaultConfig {
        applicationId = "id.altair.musevpn"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        // Situs yang dikunci. Ganti di sini kalau mau situs lain.
        buildConfigField("String", "SITE_HOST", "\"muse.ai\"")
        buildConfigField("String", "SITE_URL", "\"https://muse.ai/\"")
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        release {
            // Minify dimatikan dulu: JNI WireGuard lebih aman tanpa R8.
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    // HP saja: x86/x86_64 hanya untuk emulator. Membuangnya menghemat ~6 MB
    // karena libwg-go.so (backend Go WireGuard) yang besar ada per-arsitektur.
    packaging {
        jniLibs {
            excludes += listOf("lib/x86/**", "lib/x86_64/**")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Library resmi WireGuard (versi terbaru per riset: 1.0.20260102)
    implementation("com.wireguard.android:tunnel:1.0.20260102")
}
