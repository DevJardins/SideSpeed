package io.github.devjardins.sidespeed.helper

/** First and last TCP port the helper may listen on. Loopback (127.0.0.1) only, never other interfaces. */
const val HELPER_PORT_FIRST = 47_310
const val HELPER_PORT_LAST = 47_319
const val HELPER_PROTOCOL_VERSION = 2

/**
 * Wire protocol between SideSpeed and its shell-uid helper.
 *
 * Request: `<nonce> <verb> <args>`, one line of printable ASCII ended by "\n" (or by the client closing
 * its write side). One request per connection. The nonce matches [NONCE]: it lets this connection prove
 * it reached the real helper (see [PROVEN] and [ACK_DIR]).
 * Response: a first line [PROVEN], then the command's output lines (stdout and stderr merged),
 * then "__END__:<exit>". Requests the helper refuses get an "error: ..." line and [EXIT_REFUSED]
 * with no [PROVEN] line first.
 * `ping` is answered with [pong]; its proof is the same per-connection mechanism as every request.
 */
object HelperProtocol {
    const val END = "__END__:"
    const val MAX_REQUEST_CHARS = 512

    /** A request-line nonce: 16 to 64 ASCII letters and digits. */
    val NONCE = Regex("^[A-Za-z0-9]{16,64}$")

    /** Longest request line on the wire: a nonce, one space, and a command. */
    const val MAX_REQUEST_LINE_CHARS = MAX_REQUEST_CHARS + 64 + 1

    /**
     * First line of every proven reply. Before sending it (and before reading any install body),
     * the helper writes the request's nonce to [ACK_DIR]. Apps cannot write there, so a matching
     * ack means the real shell-uid helper answered, not another app that grabbed a stale port.
     */
    const val PROVEN = "__PROVEN__"

    /** Exit code for a request outside the allowlist, or a malformed / overlong / missing request line. */
    const val EXIT_REFUSED = 126

    /** Exit code when the helper itself failed (for example the command could not be started). */
    const val EXIT_HELPER_ERROR = 125

    /** Port file written by the helper (world-readable) so the app can find it. */
    const val PORT_FILE = "/data/local/tmp/sidespeed_helper.port"
    const val LOG_FILE = "/data/local/tmp/sidespeed_helper.log"

    /**
     * Proof of identity, one file per nonce (`<nonce>` holding `<nonce>\n`, world-readable).
     * Per-nonce files (instead of one shared file) stay correct when the helper serves several
     * connections at once. The helper prunes stale files on every write; the client never writes here.
     */
    const val ACK_DIR = "/data/local/tmp/sidespeed_helper.acks"

    /** Splits `<nonce> <request>`; null when the nonce is missing or bad, or the request is empty. */
    fun splitNonce(line: String): Pair<String, String>? {
        val space = line.indexOf(' ')
        if (space < 0) return null
        val nonce = line.substring(0, space)
        val rest = line.substring(space + 1)
        if (!NONCE.matches(nonce) || rest.isEmpty()) return null
        return nonce to rest
    }

    /** Exit code if [line] starts with [END], else null. */
    fun parseEnd(line: String): Int? = if (line.startsWith(END)) line.substring(END.length).toIntOrNull() else null

    fun pong(uid: Int, apkPath: String): String = "pong v=$HELPER_PROTOCOL_VERSION uid=$uid apk=$apkPath"
}
