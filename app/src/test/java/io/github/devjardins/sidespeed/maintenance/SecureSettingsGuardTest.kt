package io.github.devjardins.sidespeed.maintenance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SecureSettingsGuardTest {
    private val putPattern = Regex("""Settings\.(Global|Secure)\.put\w*\s*\(""")

    @Test fun onlySecureSettingsWriterCallsSettingsPutAndOnlyForAdbWifiEnabled() {
        val roots = listOf(File("src/main/java"), File("app/src/main/java"))
        val srcRoot = roots.firstOrNull { it.isDirectory }
            ?: error("Could not locate app/src/main/java from ${File("").absolutePath}")

        val callers = mutableListOf<String>()
        var writerFile: File? = null

        srcRoot.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .forEach { file ->
                val rel = file.relativeTo(srcRoot).path.replace(File.separatorChar, '/')
                val text = file.readText()
                if (rel.endsWith("maintenance/SecureSettingsWriter.kt")) {
                    writerFile = file
                }
                if (putPattern.containsMatchIn(text)) {
                    callers += rel
                }
            }

        assertEquals(
            "Only maintenance/SecureSettingsWriter.kt may call Settings.Global.put* or Settings.Secure.put*",
            listOf("io/github/devjardins/sidespeed/maintenance/SecureSettingsWriter.kt"),
            callers,
        )

        val writerText = writerFile!!.readText()
        val matches = putPattern.findAll(writerText).toList()
        assertEquals("SecureSettingsWriter must contain exactly one Settings.*.put* call", 1, matches.size)
        assertTrue(
            "SecureSettingsWriter must write Settings.Global.putInt for adb_wifi_enabled",
            writerText.contains("Settings.Global.putInt(") && writerText.contains("\"adb_wifi_enabled\""),
        )
    }
}
