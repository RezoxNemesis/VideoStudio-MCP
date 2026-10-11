plugins {
    id("com.android.application")
}

android {
    namespace = "com.rezoxnemesis.videostudio"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rezoxnemesis.videostudio.next"
        minSdk = 29
        targetSdk = 35
        versionCode = 363
        versionName = "3.6.3"
    }

    testOptions { unitTests.isIncludeAndroidResources = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // GitHub Actions explicitly points to the *restored* persistent owner
    // development keystore. AGP's automatic debug signing otherwise generates
    // a different certificate in each pull-request runner, breaking updates.
    signingConfigs {
        getByName("debug") {
            val stableKey = System.getenv("VIDEOSTUDIO_SIGNING_KEYSTORE")
            if (!stableKey.isNullOrBlank()) {
                storeFile = file(stableKey)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("androidx.core:core:1.17.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    // Free, actual GPU skinned glTF 2.0 model runtime; NOT 2D image to 3D inference.
    implementation("com.google.android.filament:filament-android:1.74.0")
    implementation("com.google.android.filament:gltfio-android:1.74.0")
    implementation("com.google.android.filament:filament-utils-android:1.74.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-transformer:1.11.1")
    implementation("androidx.media3:media3-effect:1.11.1")
    implementation("androidx.media3:media3-common:1.11.1")

    // Bundled, on-device ML. No paid inference service or cloud model dependency.
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")
    implementation("com.google.mlkit:face-mesh-detection:16.0.0-beta1")
}

