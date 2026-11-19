import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingValue(propKey: String, envKey: String): String? =
    keystoreProps.getProperty(propKey) ?: System.getenv(envKey)

android {
    namespace = "io.github.devjardins.sidespeed.companion.android"
    // compileSdk 37: the Kadb 2.1.4 AAR (via :companion) requires it. targetSdk stays 36.
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.devjardins.sidespeed.companion"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.5.2"
    }

    signingConfigs {
        // Same keystore.properties / SIDESPEED_* variables as :app. Without one, the release APK stays unsigned.
        val storeFile = signingValue("storeFile", "SIDESPEED_KEYSTORE")
        if (storeFile != null) {
            create("release") {
                this.storeFile = rootProject.file(storeFile)
                storePassword = signingValue("storePassword", "SIDESPEED_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "SIDESPEED_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "SIDESPEED_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // bcprov/bcpkix/bcutil-jdk18on 1.85 jars each carry META-INF/LICENSE.md.
        resources {
            excludes += "META-INF/LICENSE.md"
        }
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

    constraints {
        implementation(libs.bouncycastle.bcprov.jdk18on) {
            because("CVE fix: force BouncyCastle 1.85")
        }
        implementation(libs.bouncycastle.bcpkix.jdk18on) {
            because("CVE fix: force BouncyCastle 1.85")
        }
        implementation(libs.bouncycastle.bcutil.jdk18on) {
            because("CVE fix: force BouncyCastle 1.85")
        }
    }

    testImplementation(libs.junit)
}
