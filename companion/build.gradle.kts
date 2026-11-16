import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    // Same AGP 9 KMP library setup as :shared, plus Compose Multiplatform for the shared screens.
    // compileSdk 37: the Kadb 2.1.4 AAR requires it (minSdk and targetSdk stay as in the constraints).
    android {
        namespace = "io.github.devjardins.sidespeed.companion.core"
        compileSdk = 37
        minSdk = 26
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":shared"))
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kadb)
            implementation(libs.jb.compose.runtime)
            implementation(libs.jb.compose.foundation)
            implementation(libs.jb.compose.ui)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("desktopTest").dependencies {
            // Skiko natives for the host, so a desktop test can render a screen offscreen (screenshot check).
            implementation(compose.desktop.currentOs)
        }
    }
}

// Manual device check (see ManualFireTvCheck): -Psidespeed.tv=<ip>[:port] [-Psidespeed.keyDir=<dir>]
tasks.withType<Test>().matching { it.name == "desktopTest" }.configureEach {
    val tv = providers.gradleProperty("sidespeed.tv").orNull
    val keyDir = providers.gradleProperty("sidespeed.keyDir").orNull
    if (tv != null) {
        systemProperty("sidespeed.tv", tv)
        keyDir?.let { systemProperty("sidespeed.keyDir", it) }
        testLogging { showStandardStreams = true }
        outputs.upToDateWhen { false }
    }
}
