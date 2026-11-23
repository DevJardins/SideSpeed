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
    implementation(libs.kadb.mdns)
    implementation(libs.conscrypt.openjdk)
    implementation(compose.desktop.currentOs)
    implementation(libs.jb.compose.runtime)
    implementation(libs.jb.compose.foundation)
    implementation(libs.jb.compose.ui)
    implementation(libs.kotlinx.coroutines.core)

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

/** The user-visible app version, shared with the TV and Android Companion apps (gradle.properties). */
val appVersion: String = providers.gradleProperty("sidespeed.versionName").get()

/**
 * jpackage needs a major version of 1 or more on macOS and Windows, so this differs from the
 * app version: the package major is the app major plus one (0.7.0 ships as 1.7.0, 1.0.0 will
 * ship as 2.0.0), keeping every release above the previous one. The app itself reports
 * [appVersion] via the generated CompanionVersion below, never this jpackage number.
 * MSI caps each part (major.minor 255, build 65535), so the build fails early with a clear
 * message instead of producing a broken installer.
 */
val packageVersionValue = run {
    val raw = appVersion.trim().removePrefix("v")
    val parts = raw.split(".")
    fun fail(what: String): Nothing = error("$what (sidespeed.versionName=$appVersion)")
    if (parts.size != 3) fail("sidespeed.versionName must be three numeric parts (major.minor.patch)")
    val numbers = parts.map { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: fail("sidespeed.versionName must be three numeric parts (major.minor.patch)") }
    val (major, minor, patch) = numbers
    if (major + 1 > 255) fail("desktop package major ${major + 1} exceeds the MSI limit of 255")
    if (minor > 255) fail("desktop package minor $minor exceeds the MSI limit of 255")
    if (patch > 65535) fail("desktop package patch $patch exceeds the MSI limit of 65535")
    "${major + 1}.$minor.$patch"
}

/** Exposes the real app version to the UI as a generated constant, not the jpackage number. */
val generateCompanionVersion = tasks.register("generateCompanionVersion") {
    val outDir = layout.buildDirectory.dir("generated/companionVersion")
    outputs.dir(outDir)
    doLast {
        val pkgDir = outDir.get().asFile.resolve("io/github/devjardins/sidespeed/companion/desktop")
        pkgDir.mkdirs()
        pkgDir.resolve("CompanionVersion.kt").writeText(
            "package io.github.devjardins.sidespeed.companion.desktop\n\n" +
                "// Generated from sidespeed.versionName; do not edit.\n" +
                "object CompanionVersion {\n    const val APP_VERSION = \"$appVersion\"\n}\n",
        )
    }
}

kotlin {
    sourceSets.named("main") { kotlin.srcDir(generateCompanionVersion) }
}
tasks.named("compileKotlin") { dependsOn(generateCompanionVersion) }

compose.desktop {
    application {
        mainClass = "io.github.devjardins.sidespeed.companion.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "SideSpeed Companion"
            packageVersion = packageVersionValue
            description = "Keeps the SideSpeed helper running on your Fire TVs"
            vendor = "SideSpeed"
            licenseFile.set(rootProject.file("LICENSE"))
            // Kadb, Conscrypt and their crypto need these JDK modules in the trimmed runtime.
            modules("java.instrument", "java.naming", "java.sql", "jdk.crypto.ec", "jdk.unsupported")
            macOS {
                bundleID = "io.github.devjardins.sidespeed.companion"
                dockName = "SideSpeed Companion"
                iconFile.set(project.file("src/main/resources/icons/app.icns"))
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSLocalNetworkUsageDescription</key>
                        <string>SideSpeed Companion checks your TVs on your Trusted Networks.</string>
                    """.trimIndent()
                }
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
        providers.gradleProperty("sidespeed.live.ips").orNull?.let { ips -> systemProperty("sidespeed.live.ips", ips) }
        testLogging { showStandardStreams = true }
        outputs.upToDateWhen { false }
    }
}
