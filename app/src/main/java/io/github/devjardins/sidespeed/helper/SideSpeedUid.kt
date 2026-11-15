package io.github.devjardins.sidespeed.helper

/** Parses SideSpeed's own app uid out of `pm list packages -U` or `dumpsys package` output. */
object SideSpeedUid {
    /** App uids for user 0 (Process.FIRST_APPLICATION_UID..LAST_APPLICATION_UID). */
    private val APP_UIDS = 10_000..19_999

    /** From `pm list packages -U <pkg>` lines (`package:<pkg> uid:<n>`). The filter is a substring match, so match exactly. */
    fun fromPmList(lines: List<String>, pkg: String): Int? {
        val re = Regex("^package:${Regex.escape(pkg)}\\s+uid:(\\d{1,9})\\b")
        return lines.firstNotNullOfOrNull { re.find(it.trim())?.groupValues?.get(1)?.toIntOrNull() }
            ?.takeIf { it in APP_UIDS }
    }

    /** From `dumpsys package <pkg>`: the first `userId=<n>` after the `Package [<pkg>]` header. */
    fun fromDumpsys(lines: List<String>, pkg: String): Int? {
        val header = "Package [$pkg]"
        val start = lines.indexOfFirst { it.trim().startsWith(header) }
        if (start < 0) return null
        val re = Regex("\\buserId=(\\d{1,9})\\b")
        for (line in lines.drop(start + 1)) {
            if (line.trim().startsWith("Package [")) return null
            re.find(line)?.let { return it.groupValues[1].toIntOrNull()?.takeIf { uid -> uid in APP_UIDS } }
        }
        return null
    }
}
