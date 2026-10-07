plugins {
    id("com.android.application")
}

android {
    namespace = "com.rezoxnemesis.videostudio"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rezoxnemesis.videostudio"
        minSdk = 29
        targetSdk = 35
        versionCode = 12
        versionName = "1.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("androidx.media3:media3-transformer:1.11.1")
    implementation("androidx.media3:media3-effect:1.11.1")
    implementation("androidx.media3:media3-common:1.11.1")
}
