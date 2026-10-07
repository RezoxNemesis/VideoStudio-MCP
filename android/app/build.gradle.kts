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
        versionCode = 11
        versionName = "1.0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
}
