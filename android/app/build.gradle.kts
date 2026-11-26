plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/* Signing: a keystore from CI (env) if present, otherwise the shared test key in this folder */
val ksFile = System.getenv("PMC_KEYSTORE")?.let { file(it) } ?: file("../pmc-test.keystore")

android {
    namespace = "com.promulticam.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.promulticam.app"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("PMC_VERSION_CODE") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("PMC_VERSION_CODE") ?: "1")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }   // phones only: a smaller download
    }
    signingConfigs {
        create("pmc") {
            storeFile = ksFile
            storePassword = System.getenv("PMC_KS_PASS") ?: "promulticam"
            keyAlias = System.getenv("PMC_KEY_ALIAS") ?: "pmc"
            keyPassword = System.getenv("PMC_KS_PASS") ?: "promulticam"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("pmc")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false; abortOnError = false }
}

dependencies {
    val camerax = "1.4.0"
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("androidx.camera:camera-effects:$camerax")
    implementation("io.getstream:stream-webrtc-android:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
}
