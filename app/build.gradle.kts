plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.oracleairescue"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.oracleairescue"
        minSdk = 26
        targetSdk = 35
        versionCode = 37
        versionName = "1.6.5"
    }

    signingConfigs {
        create("stableLocal") {
            storeFile = file("oracleairescue-update-key.jks")
            storePassword = "oracleairescue"
            keyAlias = "oracleairescue"
            keyPassword = "oracleairescue"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("stableLocal")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stableLocal")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.github.mwiede:jsch:0.2.21")
    implementation("androidx.core:core:1.13.1")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("com.google.ai.edge.litertlm:litertlm-android:latest.release")
}
