plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.catkiss62.geniettsbenchmark"
    compileSdk = 35
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "com.catkiss62.geniettsaudition"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.3.1"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes { release { isMinifyEnabled = false } }
    packaging { jniLibs { useLegacyPackaging = true } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
}
