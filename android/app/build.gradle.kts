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
        versionCode = 349
        versionName = "3.4.9"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    testOptions { unitTests.isIncludeAndroidResources = true }
    sourceSets.getByName("main").assets.srcDir("../../protocol")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // CI restores one private development key; bind Gradle to that exact file.
    System.getenv("VIDEOSTUDIO_DEBUG_KEYSTORE")?.let { keyPath ->
        signingConfigs.getByName("debug").storeFile = file(keyPath)
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-transformer:1.11.1")
    implementation("androidx.media3:media3-effect:1.11.1")
    implementation("androidx.media3:media3-common:1.11.1")

    // Bundled, on-device ML. No paid inference service or cloud model dependency.
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")
    implementation("com.google.mlkit:face-mesh-detection:16.0.0-beta1")
}


tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED")
}
