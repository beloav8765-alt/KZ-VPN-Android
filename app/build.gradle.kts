plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val eneidaApiBaseUrl = System.getenv("ENEIDA_API_BASE_URL") ?: ""

android {
    namespace = "com.kzvpn.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kzvpn.app.v032"
        minSdk = 26
        targetSdk = 36
        versionCode = 16
        versionName = "0.8.0"
        buildConfigField("String", "CONTROL_API_BASE_URL", "\"" + eneidaApiBaseUrl.replace("\"", "\\\"") + "\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlin {
        jvmToolchain(17)
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*"
        )
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.wireguard.android:tunnel:1.0.20260102")
    implementation("com.google.zxing:core:3.5.3")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.3")
}
