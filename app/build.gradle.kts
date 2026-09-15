plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.drivestreamer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.drivestreamer"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    signingConfigs {
        getByName("debug") {
            // Fixed keystore, committed to the repo, so the SHA-1 stays
            // the same whether you build locally or via GitHub Actions —
            // that's what lets you register it with Google Cloud Console
            // once and have it keep working from any machine/CI runner.
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
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
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-ktx:1.9.1")

    // Media3 / ExoPlayer - handles playback + Android Auto session plumbing
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
    implementation("androidx.media3:media3-datasource:1.4.1")
    implementation("androidx.media3:media3-database:1.4.1")

    // Google sign-in (OAuth) — requests drive.readonly scope only
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    // Networking for Drive REST calls (files.list, files.get?alt=media)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")

    implementation("androidx.media:media:1.7.0")

    // Explicit — used directly in TokenProvider/AuthManager (Mutex, runBlocking, Dispatchers)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
