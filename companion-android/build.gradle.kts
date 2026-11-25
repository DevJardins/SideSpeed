import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import sidespeed.l10n.LanguageGate
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("sidespeed.l10n")
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingValue(propKey: String, envKey: String): String? =
    keystoreProps.getProperty(propKey) ?: System.getenv(envKey)

/** The bar from the translations plan: the Companion ships every language at or above this share of English. */
val LANGUAGE_BAR = 0.70

/**
 * The same gate :companion evaluates over its compose resources; this module only needs
 * the filters and the locales config. The summary is logged once, from :companion.
 */
val gateResult = LanguageGate.evaluate(
    rootProject.file("companion/src/commonMain/composeResources/values/strings.xml"),
    rootProject.file("companion/src/commonMain/composeResources"),
    LANGUAGE_BAR,
)

/** Writes locales_config.xml from the language gate. */
abstract class GenerateLocalesConfig : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resDir: DirectoryProperty

    @get:Input
    abstract val bar: Property<Double>

    @get:OutputDirectory
    abstract val resOutDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val result = LanguageGate.evaluate(
            resDir.get().asFile.resolve("values/strings.xml"),
            resDir.get().asFile,
            bar.get(),
        )
        val tags = result.included.map { it.tag }
        val xmlDir = resOutDir.get().asFile.resolve("xml")
        xmlDir.mkdirs()
        val locales = listOf("en") + tags
        xmlDir.resolve("locales_config.xml").writeText(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<locale-config xmlns:android=\"http://schemas.android.com/apk/res/android\">\n" +
                locales.joinToString("\n") { "    <locale android:name=\"$it\" />" } + "\n" +
                "</locale-config>\n",
        )
    }
}

val generateLocalesConfig = tasks.register<GenerateLocalesConfig>("generateLocalesConfig") {
    resDir.set(rootProject.file("companion/src/commonMain/composeResources"))
    bar.set(LANGUAGE_BAR)
    resOutDir.set(layout.buildDirectory.dir("generated/l10n/res"))
}

// AGP 9 removed the Provider/File overloads of addGeneratedSourceDirectory, so the typed
// task above wires its output directory to the variant sources that consume it.
androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(
            generateLocalesConfig,
            GenerateLocalesConfig::resOutDir,
        )
    }    // Pseudo-locales are on only in debug, so only debug keeps their resources.
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.androidResources.localeFilters.addAll(LanguageGate.PSEUDO_LOCALE_FILTERS)
    }
}

android {
    namespace = "io.github.devjardins.sidespeed.companion.android"
    // compileSdk 37: the Kadb 2.1.4 AAR (via :companion) requires it. targetSdk stays 36.
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.devjardins.sidespeed.companion"
        minSdk = 26
        targetSdk = 36
        // Same version as the TV app: the single source is sidespeed.versionName in
        // gradle.properties, computed by the root build script (0.7.0 -> 700).
        versionCode = rootProject.extra["sidespeedVersionCode"] as Int
        versionName = providers.gradleProperty("sidespeed.versionName").get()
    }

    // Ships only English plus the languages at or above the gate; debug adds the
    // pseudo-locales below (androidComponents). The filters go straight to aapt2 -c,
    // which wants qualifier syntax (en-rXA), not BCP-47 (en-XA).
    androidResources {
        localeFilters += LanguageGate.releaseLocaleFilters(gateResult)
    }

    buildFeatures {
        buildConfig = true
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
        debug {
            isPseudoLocalesEnabled = true
        }
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
    implementation(libs.kadb.mdns)
    implementation(libs.conscrypt.android)
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
