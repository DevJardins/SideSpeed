import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.jetbrains.compose)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":companion"))
    implementation(compose.desktop.currentOs)
    implementation(libs.jb.compose.runtime)
    implementation(libs.jb.compose.foundation)
    implementation(libs.jb.compose.ui)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}

compose.desktop {
    application {
        mainClass = "io.github.devjardins.sidespeed.companion.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "SideSpeed Companion"
            // jpackage needs a major version of 1 or more on macOS and Windows, so this differs from the 0.x app versions.
            packageVersion = "1.0.0"
            description = "Keeps the SideSpeed helper running on your Fire TVs"
            vendor = "SideSpeed"
            licenseFile.set(rootProject.file("LICENSE"))
            // Kadb and its crypto need these JDK modules in the trimmed runtime.
            modules("java.naming", "java.sql", "jdk.crypto.ec", "jdk.unsupported")
            macOS {
                bundleID = "io.github.devjardins.sidespeed.companion"
                dockName = "SideSpeed Companion"
                iconFile.set(project.file("src/main/resources/icons/app.icns"))
            }
            windows {
                menuGroup = "SideSpeed"
                iconFile.set(project.file("src/main/resources/icons/app.ico"))
                upgradeUuid = "6f7a3d3e-4c1b-4a55-9d0e-2b6c9f1a7d42"
            }
            linux {
                packageName = "sidespeed-companion"
                iconFile.set(project.file("src/main/resources/icons/app.png"))
            }
        }
    }
}

tasks.test {
    providers.gradleProperty("sidespeed.live").orNull?.let {
        systemProperty("sidespeed.live", it)
        testLogging { showStandardStreams = true }
        outputs.upToDateWhen { false }
    }
}
