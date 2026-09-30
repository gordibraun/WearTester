plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.weartester.watchface"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.weartester.watchface"
        minSdk = 34
        targetSdk = 34
        versionCode = 9
        versionName = "1.8"
    }

    buildTypes {
        debug {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = false
        aidl = false
        renderScript = false
        shaders = false
        resValues = false
    }
}
