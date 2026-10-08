plugins {
    id("com.android.application")
}

android {
    namespace = "com.rezoxnemesis.videostudio"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rezoxnemesis.videostudio"
        minSdk = 29
        targetSdk = 35
        testInstrumentationRunner = "com.rezoxnemesis.videostudio.CloudSmokeInstrumentation"
        versionCode = 360
        versionName = "3.6.0"
        manifestPlaceholders["appLabel"] = "VideoStudio"
    }

    testOptions { unitTests.isIncludeAndroidResources = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        getByName("debug") {
            System.getenv("VIDEOSTUDIO_SIGNING_KEY")?.let { storeFile = file(it) }
        }
    }

    buildTypes {
        create("mobile") {
            initWith(getByName("debug"))
            signingConfig = signingConfigs.getByName("debug")
            ndk { abiFilters += "arm64-v8a" }
            matchingFallbacks += listOf("debug")
        }
        create("preview") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
            ndk { abiFilters += "arm64-v8a" }
            manifestPlaceholders["appLabel"] = "VideoStudio Preview"
            matchingFallbacks += listOf("debug")
        }
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    implementation("androidx.media3:media3-transformer:1.11.1")
    implementation("androidx.media3:media3-effect:1.11.1")
    implementation("androidx.media3:media3-common:1.11.1")

    // Bundled, on-device ML. No paid inference service or cloud model dependency.
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")
    implementation("com.google.mlkit:face-mesh-detection:16.0.0-beta1")
}
