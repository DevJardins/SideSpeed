package io.github.devjardins.sidespeed.helper

/** First and last TCP port the helper may listen on. Loopback (127.0.0.1) only, never other interfaces. */
const val HELPER_PORT_FIRST = 47_310
const val HELPER_PORT_LAST = 47_319
const val HELPER_PROTOCOL_VERSION = 1

/**
 * Wire protocol between SideSpeed and its shell-uid helper.
 *
 * Request: one line of printable ASCII, "<verb> <args>", at most [MAX_REQUEST_CHARS] chars, ended by "\n"
 * (or by the client closing its write side). One request per connection.
 * Response: the command's output lines (stdout and stderr merged), then "__END__:<exit>".
 * Requests the helper refuses get an "error: ..." line and [EXIT_REFUSED].
 * `ping <nonce>` ([A-Za-z0-9]{16,64}) makes the helper write the nonce to [ACK_FILE] before it answers.
 */
object HelperProtocol {
    const val END = "__END__:"
    const val MAX_REQUEST_CHARS = 512

    /** Exit code for a request outside the allowlist, or a malformed / overlong / missing request line. */
    const val EXIT_REFUSED = 126

    /** Exit code when the helper itself failed (for example the command could not be started). */
    const val EXIT_HELPER_ERROR = 125

    /** Port file written by the helper (world-readable) so the app can find it. */
    const val PORT_FILE = "/data/local/tmp/sidespeed_helper.port"
    const val LOG_FILE = "/data/local/tmp/sidespeed_helper.log"

    /**
     * Proof of identity for `ping <nonce>`: before answering, the helper writes the nonce here (atomically,
     * world-readable). Apps cannot write /data/local/tmp, so a matching ack means the real shell-uid helper
     * answered, not another app that grabbed a stale port.
     */
    const val ACK_FILE = "/data/local/tmp/sidespeed_helper.ack"

    /** Exit code if [line] starts with [END], else null. */
    fun parseEnd(line: String): Int? = if (line.startsWith(END)) line.substring(END.length).toIntOrNull() else null

    fun pong(uid: Int, apkPath: String): String = "pong v=$HELPER_PROTOCOL_VERSION uid=$uid apk=$apkPath"
}
