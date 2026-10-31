plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}

android {
    namespace = "com.example.weartester"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.weartester"
        minSdk = 30
        // 32 on purpose: from 33 on an app may no longer switch Bluetooth off and on (the wedge cure).
        targetSdk = 32
        versionCode = 23
        versionName = "1.22"

    }
    sourceSets.getByName("main").java.srcDir("../shared/src/main/java")

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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    implementation(libs.play.services.wearable)
    implementation(libs.androidx.watchface)
    implementation(libs.androidx.watchface.style)
    implementation(libs.androidx.watchface.complications.data.source)
}
