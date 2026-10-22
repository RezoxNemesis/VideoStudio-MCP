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
        versionCode = 355
        versionName = "3.5.5"
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // AGP may resolve its default debug key through ANDROID_USER_HOME rather
    // than HOME. Never let that replace the owner's established identity.
    signingConfigs {
        getByName("debug") {
            val verifiedKey = System.getenv("VIDEOSTUDIO_SIGNING_KEYSTORE")
                ?: "${System.getenv("HOME")}/.android/debug.keystore"
            storeFile = file(verifiedKey)
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("debug") }
        release { isMinifyEnabled = false }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-transformer:1.11.1")
    implementation("androidx.media3:media3-effect:1.11.1")
    implementation("androidx.media3:media3-common:1.11.1")
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")
    implementation("com.google.mlkit:face-mesh-detection:16.0.0-beta1")
}
