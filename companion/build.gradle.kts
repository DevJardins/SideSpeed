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
            implementation(libs.kadb.mdns)
            implementation(libs.jb.compose.runtime)
            implementation(libs.jb.compose.foundation)
            implementation(libs.jb.compose.ui)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("desktopMain").dependencies {
            implementation(libs.bouncycastle.bctls.jdk18on)
        }
        getByName("androidMain").dependencies {
            implementation(libs.conscrypt.android)
        }
        getByName("desktopTest").dependencies {
            // Skiko natives for the host, so a desktop test can render a screen offscreen (screenshot check).
            implementation(compose.desktop.currentOs)
        }
    }
}

// Dependabot: kadb 2.1.4 pulls bcprov/bcpkix/bcutil-jdk18on 1.84, and bcprov < 1.85
// is flagged. The sibling app modules already force 1.85; do the same here for
// the android, desktop and test graphs, including bctls-jdk18on. This module's
// alignment keeps the dependency-submission snapshot clean; companion-desktop's
// own constraint only covers its own graph.
val bouncyCastle = libs.versions.bouncycastle.get()
configurations.matching { it.isCanBeResolved }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.bouncycastle") {
            useVersion(bouncyCastle)
        }
    }
}

// Manual device check (see ManualFireTvCheck): -Psidespeed.tv=<ip>[:port] [-Psidespeed.keyDir=<dir>]
tasks.withType<Test>().matching { it.name == "desktopTest" }.configureEach {
    systemProperty("sidespeed.previewDir", layout.buildDirectory.dir("previews").get().asFile.absolutePath)
    val tv = providers.gradleProperty("sidespeed.tv").orNull
    val keyDir = providers.gradleProperty("sidespeed.keyDir").orNull
    if (tv != null) {
        systemProperty("sidespeed.tv", tv)
        keyDir?.let { systemProperty("sidespeed.keyDir", it) }
        testLogging { showStandardStreams = true }
        outputs.upToDateWhen { false }
    }
}
