package sidespeed.l10n

import org.gradle.api.Plugin
import org.gradle.api.Project

class L10nPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        if (project != project.rootProject) return
        // Aggregate check over both modules, so one task guards every translation.
        // Module builds keep applying this plugin; it only registers the task on the root.
        project.tasks.register("checkTranslations", CheckTranslationsTask::class.java) {
            group = "verification"
            description = "Checks translated strings.xml files against English and writes build/l10n/translation-summary.md."
            englishTv.set(project.file("app/src/main/res/values/strings.xml"))
            resTv.set(project.file("app/src/main/res"))
            englishCompanion.set(project.file("companion/src/commonMain/composeResources/values/strings.xml"))
            resCompanion.set(project.file("companion/src/commonMain/composeResources"))
            bar.set(0.70)
            baseRef.set(project.providers.gradleProperty("l10nBase"))
            summaryFile.set(project.layout.buildDirectory.file("l10n/translation-summary.md"))
        }
    }
}
