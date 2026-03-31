plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace  = "com.h2x.sensor"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.h2x.sensor"
        minSdk        = 26
        targetSdk     = 36
        versionCode   = 1
        versionName   = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.coroutines.android)
    implementation("com.google.android.gms:play-services-location:21.3.0")
}
