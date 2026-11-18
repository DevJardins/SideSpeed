plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "io.github.devjardins.sidespeed.baselineprofile"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Generates into :app; the profile lands in app/src/main/generated/baselineProfiles.
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.test.ext.junit)

    constraints {
        // Dependabot (CVE-2026-63126): benchmark-traceprocessor pulls
        // wire-runtime 6.4.0; force the patched 6.4.5.
        implementation(libs.build.wire.runtime) {
            because("Dependabot: wire-runtime <= 6.4.4, patched in 6.4.5")
        }
    }
}
