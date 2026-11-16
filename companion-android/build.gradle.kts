plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.devjardins.sidespeed.companion.android"
    // compileSdk 37: the Kadb 2.1.4 AAR (via :companion) requires it. targetSdk stays 36.
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.devjardins.sidespeed.companion"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
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

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":companion"))
    implementation(libs.jb.compose.runtime)
    implementation(libs.jb.compose.foundation)
    implementation(libs.jb.compose.ui)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.work.runtime)
    testImplementation(libs.junit)
}
