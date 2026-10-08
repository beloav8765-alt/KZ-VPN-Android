plugins {
    id("com.android.application")
}

android {
    namespace = "dev.eneida.prototype"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.eneida.prototype.s25"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
