package io.github.devjardins.sidespeed.companion.store

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

/** Owner-only permissions, as for the ADB key: enforced on POSIX, best effort elsewhere (Windows). */
internal object OwnerOnly {
    /** @throws java.io.IOException if POSIX permissions cannot be applied. */
    fun restrict(f: File, directory: Boolean) {
        val path = f.toPath()
        if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java) != null) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------"))
        } else {
            f.setReadable(false, false)
            f.setWritable(false, false)
            f.setReadable(true, true)
            f.setWritable(true, true)
            if (directory) {
                f.setExecutable(false, false)
                f.setExecutable(true, true)
            }
        }
    }
}
