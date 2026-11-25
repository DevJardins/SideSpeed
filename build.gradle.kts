// Dependabot (manifest settings.gradle.kts): the Gradle plugin classpath pulls old
// transitive deps (AGP -> guava 33.4.0, commons-compress -> commons-lang3 3.16.0,
// jetifier -> jdom2 2.0.6, assorted -> jose4j 0.9.5 / BC 1.80.2). Floors below keep
// ./gradlew buildEnvironment above the patched versions. Versions live in
// gradle/libs.versions.toml (build-* libraries).
buildscript {
    dependencies {
        constraints {
            classpath(libs.build.guava) {
                because("Dependabot: guava <= 33.7.1, patched in 33.7.2")
            }
            classpath(libs.build.bcprov.jdk18on) {
                because("Dependabot: bcprov-jdk18on < 1.85, patched in 1.85")
            }
            classpath(libs.build.bcpkix.jdk18on) {
                because("Dependabot: bcpkix-jdk18on < 1.84, patched in 1.84")
            }
            classpath(libs.build.bcutil.jdk18on) {
                because("Dependabot: follows bcpkix-jdk18on 1.84")
            }
            classpath(libs.build.jose4j) {
                because("Dependabot: jose4j < 0.9.6, patched in 0.9.6")
            }
            classpath(libs.build.jdom2) {
                because("Dependabot: jdom2 < 2.0.6.1, patched in 2.0.6.1")
            }
            classpath(libs.build.commons.lang3) {
                because("Dependabot: commons-lang3 < 3.18.0, patched in 3.18.0")
            }
            classpath(libs.build.httpclient) {
                because("Dependabot: httpclient < 4.5.13, patched in 4.5.13")
            }
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.jetbrains.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
    id("sidespeed.l10n")
}

/**
 * Single source for the Android versionCode of the TV app and the Companion app: derived from
 * sidespeed.versionName in gradle.properties as major*10000 + minor*100 + patch (0.7.0 -> 700),
 * so a version bump never needs a hand-maintained versionCode. Consumed via
 * `val sidespeedVersionCode: Int by rootProject.extra`. Minor and patch must stay below 100 to
 * keep the scheme collision-free; the build fails early otherwise.
 */
val sidespeedVersionCode: Int by extra(
    providers.gradleProperty("sidespeed.versionName").get().let { versionName ->
        val parts = versionName.trim().removePrefix("v").split(".")
        fun fail(what: String): Nothing = error("$what (sidespeed.versionName=$versionName)")
        if (parts.size != 3) fail("sidespeed.versionName must be three numeric parts (major.minor.patch)")
        val numbers = parts.map {
            it.toIntOrNull()?.takeIf { n -> n >= 0 }
                ?: fail("sidespeed.versionName must be three numeric parts (major.minor.patch)")
        }
        val (major, minor, patch) = numbers
        if (minor >= 100) fail("minor version $minor must be below 100 (versionCode = major*10000 + minor*100 + patch)")
        if (patch >= 100) fail("patch version $patch must be below 100 (versionCode = major*10000 + minor*100 + patch)")
        major * 10000 + minor * 100 + patch
    },
)

// AGP's standalone lint tool classpath (androidLintTool) resolves its own old
// copies (guava 33.4.0, BC 1.80.2, httpclient 4.5.6, commons-lang3 3.16.0),
// which the dependency-submission snapshot also reports. Align every module.
val lintGuava = libs.versions.guavaBuild.get()
val lintBcProv = libs.versions.bouncycastle.get()
val lintBcPkix = libs.versions.bouncycastlePkixBuild.get()
val lintHttpclient = libs.versions.httpclientBuild.get()
val lintLang3 = libs.versions.commonsLang3Build.get()
subprojects {
    configurations.matching { it.name == "androidLintTool" }.configureEach {
        resolutionStrategy.eachDependency {
            when (requested.group to requested.name) {
                "com.google.guava" to "guava" -> useVersion(lintGuava)
                "org.bouncycastle" to "bcprov-jdk18on" -> useVersion(lintBcProv)
                "org.bouncycastle" to "bcpkix-jdk18on" -> useVersion(lintBcPkix)
                "org.bouncycastle" to "bcutil-jdk18on" -> useVersion(lintBcPkix)
                "org.apache.httpcomponents" to "httpclient" -> useVersion(lintHttpclient)
                "org.apache.commons" to "commons-lang3" -> useVersion(lintLang3)
            }
        }
    }
}
