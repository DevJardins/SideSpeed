package sidespeed.l10n

import org.gradle.api.Plugin
import org.gradle.api.Project

class L10nPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // No-op: applying this plugin only puts the gate classes on build-script classpaths.
    }
}
