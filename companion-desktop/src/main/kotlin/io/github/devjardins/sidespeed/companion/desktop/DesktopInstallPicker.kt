package io.github.devjardins.sidespeed.companion.desktop

import io.github.devjardins.sidespeed.companion.install.InstallSource
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/** A local APK or split bundle. The archive is opened once, on first use, and closed with the source. */
class FileInstallSource(private val file: File) : InstallSource {
    override val displayName: String get() = file.name
    override val sizeBytes: Long? get() = file.length().takeIf { file.isFile }
    private var zip: ZipFile? = null
    private var closed = false

    override fun openStream(): InputStream = file.inputStream()

    @Synchronized
    override fun asZipFile(): ZipFile? {
        if (closed) return null
        zip?.let { return it }
        return try { ZipFile(file).also { zip = it } } catch (_: Exception) { null }
    }

    @Synchronized
    override fun close() {
        closed = true
        runCatching { zip?.close() }
        zip = null
    }
}

object DesktopInstallPicker {
    val EXTENSIONS = listOf("apk", "apks", "xapk", "apkm", "zip")

    fun isInstallFileName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in EXTENSIONS

    /** Shows the system file picker. Returns the chosen file, or null if cancelled. Call from the UI thread. */
    fun pickFile(parentWindow: Frame?): File? {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        if (os.startsWith("linux")) {
            // AWT's FileDialog on Linux is a plain Motif dialog; Swing's chooser is friendlier.
            val chooser = JFileChooser().apply {
                isAcceptAllFileFilterUsed = false
                fileFilter = FileNameExtensionFilter(EXTENSIONS.joinToString(", "), *EXTENSIONS.toTypedArray())
            }
            return if (chooser.showOpenDialog(parentWindow) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
        }
        // No title: any text here would be English only.
        val dialog = FileDialog(parentWindow, "", FileDialog.LOAD)
        if (os.startsWith("windows")) dialog.file = EXTENSIONS.joinToString(";") { "*.$it" }
        dialog.setFilenameFilter { _, name -> isInstallFileName(name) }
        dialog.isVisible = true
        val name = dialog.file ?: return null
        return File(dialog.directory ?: return null, name)
    }
}
