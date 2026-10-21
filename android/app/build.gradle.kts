import java.security.KeyStore
import java.security.MessageDigest

plugins {
    id("com.android.application")
}

// Private proof fixtures/output directories are intentionally outside Git and
// can change between visual generations without changing compiled test classes.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    if (listOf("VIDEOSTUDIO_REFERENCE_FIDELITY_FIXTURE", "VIDEOSTUDIO_OWNER_DRAWING_ASSET",
            "VIDEOSTUDIO_LAYERED_DRAWING_EVIDENCE", "VIDEOSTUDIO_AUTHORED_SCENE_MANIFEST", "VIDEOSTUDIO_AUTHORED_MOTION_MANIFEST", "VIDEOSTUDIO_OWNED_EDITOR_MANIFEST", "VIDEOSTUDIO_LOCAL_DRAWING_MODEL", "VIDEOSTUDIO_LOCAL_DRAWING_STUDY", "VIDEOSTUDIO_PAIRED_STUDY", "VIDEOSTUDIO_PAIRED_MODEL_PROBE", "VIDEOSTUDIO_PLUS_GRAPH_CONVERSION", "VIDEOSTUDIO_PLUS_GRAPH_PROBE").any { !System.getenv(it).isNullOrBlank() }) {
        outputs.upToDateWhen { false }
    }
}

android {
    namespace = "com.rezoxnemesis.videostudio"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rezoxnemesis.videostudio.next"
        minSdk = 29
        targetSdk = 35
        versionCode = 510
        versionName = "4.10.7"
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
            // Null prevents AGP from generating its default replacement key.
            storeFile = null
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
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

// Unit tests need no APK signing key. Every installable build requires the
// pre-existing owner key and checks its public identity BEFORE packaging.
val verifyContinuitySigning = tasks.register("verifyContinuitySigning") {
    doLast {
        val configured = System.getenv("VIDEOSTUDIO_SIGNING_KEYSTORE")
        require(!configured.isNullOrBlank()) {
            "Original VideoStudio signing key unavailable; refusing a replacement or unsigned APK"
        }
        val original = file(configured)
        require(original.isFile && original.canRead()) { "Original VideoStudio signing key is not readable" }
        val keyStore = KeyStore.getInstance(original, "android".toCharArray())
        require(keyStore.isKeyEntry("androiddebugkey")) { "Original VideoStudio signing alias unavailable" }
        val certificate = keyStore.getCertificate("androiddebugkey")
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded).joinToString("") { "%02x".format(it.toInt() and 255) }
        require(fingerprint == "7e0187470356616be2a45bc7f41445894085185db110edfdc0cdfd232942a6dd") {
            "VideoStudio signing identity differs from the pinned original certificate"
        }
    }
}
tasks.matching {
    it.name == "validateSigningDebug" || it.name == "validateSigningRelease" ||
        it.name == "packageDebug" || it.name == "packageRelease"
}.configureEach { dependsOn(verifyContinuitySigning) }

dependencies {
    implementation("androidx.core:core:1.15.0")
    // Real on-device neural inference; model weights remain optional private packs.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
    testRuntimeOnly("com.microsoft.onnxruntime:onnxruntime:1.23.2")
    implementation(files("libs/clipper2-java-2.0.1b.jar"))
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

