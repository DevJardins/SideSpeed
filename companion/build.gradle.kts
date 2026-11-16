import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    // Same AGP 9 KMP library setup as :shared. Compose Multiplatform is added in Task 6.
    android {
        namespace = "io.github.devjardins.sidespeed.companion.core"
        compileSdk = 36
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
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
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
