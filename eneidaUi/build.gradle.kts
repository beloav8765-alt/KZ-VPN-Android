plugins {
    id("com.android.application")
}

android {
    namespace = "dev.eneida.prototype"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.eneida.prototype.desktop.v5"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.5.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
