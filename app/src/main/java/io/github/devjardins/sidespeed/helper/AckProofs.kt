package io.github.devjardins.sidespeed.helper

import java.io.File
import java.io.IOException

/**
 * Per-nonce proof files for the shell-uid helper (see [HelperProtocol.ACK_DIR]).
 *
 * One file per nonce (`<nonce>` holding `<nonce>\n`) instead of one shared file, so concurrent
 * connections each prove themselves with no overwrite. Plain JVM (no android.*) so it is unit-tested;
 * [HelperMain] supplies the chmod that makes the files and the directory world-readable.
 */
object AckProofs {
    /** At most this many proof files are kept; the oldest go first. */
    const val MAX_FILES = 64

    /** Proof files older than this are deleted on every write. */
    const val MAX_AGE_MS = 60_000L

    const val DIR_MODE = 493 // 0755: the app must list and read the directory
    const val FILE_MODE = 420 // 0644: the app must read the proof

    /**
     * Writes the proof for [nonce]: temp file, chmod, rename, so a reader never sees a partial nonce.
     * Then prunes stale files. Throws on failure: the caller must answer with an error, never as proven.
     */
    fun write(ackDir: File, nonce: String, chmod: (file: File, mode: Int) -> Unit = { _, _ -> }) {
        require(HelperProtocol.NONCE.matches(nonce)) { "bad nonce" }
        // mkdirs returns false when another thread created the directory first, so re-check.
        if (!ackDir.isDirectory && !ackDir.mkdirs() && !ackDir.isDirectory) {
            throw IOException("cannot create ${ackDir.path}")
        }
        chmod(ackDir, DIR_MODE)
        val tmp = File(ackDir, "$nonce.tmp")
        tmp.delete()
        tmp.writeText("$nonce\n")
        chmod(tmp, FILE_MODE)
        if (!tmp.renameTo(File(ackDir, nonce))) throw IOException("rename to ${ackDir.path}/$nonce failed")
        prune(ackDir, keep = nonce)
    }

    /** True when `<nonce>` in [ackDir] holds [nonce]. The client never writes; only the helper can make this true. */
    fun matches(ackDir: File, nonce: String): Boolean {
        if (!HelperProtocol.NONCE.matches(nonce)) return false
        return try {
            File(ackDir, nonce).readText().trim() == nonce
        } catch (_: IOException) {
            false
        }
    }

    private fun prune(ackDir: File, keep: String) {
        val now = System.currentTimeMillis()
        val files = ackDir.listFiles()?.filter { it.isFile } ?: return
        // Stale files go, including temp files a crashed write left behind. A concurrent write's
        // temp file is seconds old, so the age gate never catches it.
        files.filter { (now - it.lastModified()) > MAX_AGE_MS }.forEach { it.delete() }
        val proofs = (ackDir.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") } ?: return)
            .filter { it.name != keep }
            .sortedWith(compareBy({ it.lastModified() }, { it.name }))
        val over = proofs.size + 1 - MAX_FILES
        if (over > 0) proofs.take(over).forEach { it.delete() }
    }
}
