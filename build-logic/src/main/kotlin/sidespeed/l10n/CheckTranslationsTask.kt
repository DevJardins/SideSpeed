package sidespeed.l10n

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Runs [TranslationCheck] over the TV and Companion translations.
 *
 * Writes the Markdown summary to [summaryFile] and fails the build when any
 * translation breaks a rule. The optional [baseRef] (`-Pl10nBase=<ref>`) adds
 * per-language changed-string counts compared with that git ref.
 */
abstract class CheckTranslationsTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val englishTv: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resTv: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val englishCompanion: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resCompanion: DirectoryProperty

    @get:Input
    abstract val bar: Property<Double>

    @get:Input
    @get:Optional
    abstract val baseRef: Property<String>

    @get:OutputFile
    abstract val summaryFile: RegularFileProperty

    @TaskAction
    fun run() {
        val modules = listOf(
            TranslationCheck.Module("TV", englishTv.get().asFile, resTv.get().asFile),
            TranslationCheck.Module(
                "Companion",
                englishCompanion.get().asFile,
                resCompanion.get().asFile,
                TranslationCheck.Format.COMPOSE,
            ),
        )
        val changed = if (baseRef.isPresent) {
            collectChanged(project.projectDir, baseRef.get(), modules)
        } else {
            emptyMap()
        }
        val result = TranslationCheck.check(modules, bar.get(), baseRef.orNull, changed, project.projectDir)
        summaryFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(result.markdown)
        }
        if (result.errors.isNotEmpty()) {
            throw GradleException(
                "Translation check found ${result.errors.size} problem(s):\n" +
                    result.errors.joinToString("\n"),
            )
        }
        logger.lifecycle("Translation check passed.")
    }

    private fun collectChanged(
        gitDir: File,
        base: String,
        modules: List<TranslationCheck.Module>,
    ): Map<String, Set<String>> {
        val mergeBase = git(gitDir, "merge-base", "HEAD", base)
            ?: throw GradleException(
                "Cannot compare translations with base \"$base\": git merge-base failed. " +
                    "Fetch the base first, for example with git fetch origin.",
            )
        val changed = mutableMapOf<String, Set<String>>()
        for (module in modules) {
            for (file in TranslationCheck.translationFiles(module.resDir)) {
                val rel = gitDir.toPath().relativize(file.toPath()).toString()
                val baseContent = git(gitDir, "show", "$mergeBase:$rel")
                changed[file.path] = TranslationCheck.changedKeys(baseContent, file.readText())
            }
        }
        return changed
    }

    /** Runs git and returns trimmed stdout, or null when git fails. */
    private fun git(dir: File, vararg args: String): String? = try {
        val process = ProcessBuilder("git", *args).directory(dir)
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        if (code == 0) output.trim() else null
    } catch (e: Exception) {
        null
    }
}
