plugins {
    id("com.android.application")
}

android {
    namespace = "com.absecuritas.realtimeinspector"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.absecuritas.realtimeinspector"
        minSdk = 23
        targetSdk = 36
        versionCode = 15
        versionName = "13.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.12.3")

    // CameraX stable 1.5.3
    implementation("androidx.camera:camera-core:1.5.3")
    implementation("androidx.camera:camera-camera2:1.5.3")
    implementation("androidx.camera:camera-lifecycle:1.5.3")
    implementation("androidx.camera:camera-view:1.5.3")

    // Bundled ML Kit barcode model: available immediately after install.
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
}
