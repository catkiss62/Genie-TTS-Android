plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val skipNativeBuild = providers.gradleProperty("skipNativeBuild")
    .map(String::toBoolean)
    .orElse(false)
    .get()

android {
    namespace = "com.catkiss62.geniettsbenchmark"
    compileSdk = 35
    buildToolsVersion = "37.0.0"
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "com.catkiss62.geniettsaudition"
        minSdk = 26
        targetSdk = 35
        versionCode = 26
        versionName = "0.8.0"
        ndk { abiFilters += "arm64-v8a" }
        if (!skipNativeBuild) {
            externalNativeBuild {
                cmake { cppFlags += "-std=c++17" }
            }
        }
    }
    if (!skipNativeBuild) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
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
    testImplementation("junit:junit:4.13.2")
}

val verifyJiuhuOnlyAssets = tasks.register("verifyJiuhuOnlyAssets") {
    doLast {
        val retiredTiandouAssets = file("src/main/assets/benchmark")
        check(!retiredTiandouAssets.exists()) {
            "v0.8.0 is Jiuhu-only: remove app/src/main/assets/benchmark before building"
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(verifyJiuhuOnlyAssets)
}
