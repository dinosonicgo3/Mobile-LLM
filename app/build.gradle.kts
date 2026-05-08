plugins {
    id("com.android.application")
}

android {
    namespace = "com.oracleairescue"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.oracleairescue"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.3.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
}
