plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.catkiss62.geniettsbenchmark"
    compileSdk = 35
    buildToolsVersion = "37.0.0"
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "com.catkiss62.geniettsaudition"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "0.6.0"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake { cppFlags += "-std=c++17" }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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
