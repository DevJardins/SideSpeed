package io.github.devjardins.sidespeed.companion.tv

import com.flyfishxu.kadb.cert.KadbCert
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class CompanionKeysTest {
    @Test
    fun keyIsGeneratedOnceAndReusedAcrossConfigurations() {
        val dir = Files.createTempDirectory("sidespeed-keys").toFile()
        try {
            val keyDir = dir.resolve("adb")
            CompanionKeys.ensureReady(keyDir)
            val file = keyDir.resolve(CompanionKeys.KEY_FILE_NAME)
            assertTrue(file.isFile)
            val first = file.readBytes()
            assertTrue(String(first).contains("PRIVATE KEY"))

            // A fresh process: Kadb's singleton reloads the saved key rather than generating a new one.
            CompanionKeys.ensureReady(dir.resolve("elsewhere"))
            CompanionKeys.ensureReady(keyDir)
            assertContentEquals(first, file.readBytes())
            assertContentEquals(first, KadbCert.exportPrivateKeyOrNull())
            assertTrue(keyDir.listFiles()!!.none { it.name.endsWith(".tmp") })
        } finally {
            dir.deleteRecursively()
        }
    }
}
