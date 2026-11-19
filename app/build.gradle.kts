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
    namespace = "io.github.devjardins.sidespeed"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.devjardins.sidespeed"
        minSdk = 24
        targetSdk = 36
        versionCode = 5
        versionName = "0.5.0"
        buildConfigField("String", "UPDATE_BASE_URL", "\"https://github.com/DevJardins/SideSpeed/releases/download\"")
    }

    signingConfigs {
        // Only created when a keystore is supplied; otherwise the release APK stays unsigned.
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
        // Debug serves updates from -PupdateBaseUrl (the emulator E2E); release is always GitHub.
        debug {
            val base = (findProperty("updateBaseUrl") as String?)?.takeIf { it.isNotBlank() }
                ?: "https://github.com/DevJardins/SideSpeed/releases/download"
            buildConfigField("String", "UPDATE_BASE_URL", "\"$base\"")
        }
        release {
            isMinifyEnabled = true
            signingConfigs.findByName("release")?.let { signingConfig = it }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Non-debuggable but debug-key-signed, so ART AOT-compiles it and it installs over a debug
        // install without an uninstall. Used for on-device runs (baseline-profile generation and the
        // kill/restart check); never shipped.
        create("benchmark") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
        // Same shape as benchmark, but named to match the :baselineprofile module's nonMinifiedRelease
        // test variant: without this, generation targets the unsigned release APK and cannot install.
        create("nonMinifiedRelease") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.datastore.preferences)
    // Installs the baseline profile at install time (transitively present before; now explicit).
    implementation(libs.androidx.profileinstaller)
    implementation(libs.libadb.android)
    implementation(libs.sun.security.android)
    implementation(libs.conscrypt.android)
    implementation(libs.hiddenapibypass)
    implementation(libs.zxing.core)

    constraints {
        implementation(libs.bouncycastle.bcprov.jdk15to18) {
            because("CVE fix: force BouncyCastle 1.85")
        }
    }

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
