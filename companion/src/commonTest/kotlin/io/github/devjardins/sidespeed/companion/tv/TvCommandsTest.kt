package io.github.devjardins.sidespeed.companion.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TvCommandsTest {
    private val id = "0123456789abcdef"
    private val secret = "ab".repeat(32)
    private val nonce = "cd".repeat(32)

    @Test fun writeProveAndForgetUseTheCheckedHexOnly() {
        assertEquals(
            "umask 077 && mkdir -p /data/local/tmp/.sidespeed-id && " +
                "printf '%s' $secret > /data/local/tmp/.sidespeed-id/$id.tmp && " +
                "mv -f /data/local/tmp/.sidespeed-id/$id.tmp /data/local/tmp/.sidespeed-id/$id",
            TvCommands.writeSecret(id, secret),
        )
        assertEquals(
            "if command -v sha256sum >/dev/null 2>&1; then h=sha256sum; else h=sha1sum; fi; " +
                "{ cat /data/local/tmp/.sidespeed-id/$id && printf '%s' $nonce; } | \$h",
            TvCommands.proveSecret(id, nonce),
        )
        assertEquals(
            "rm -f /data/local/tmp/.sidespeed-id/$id",
            TvCommands.forgetSecret(id),
        )
    }

    @Test fun identityCommandsRefuseAnythingThatIsNotLowercaseHexOfTheRightLength() {
        val bad = listOf(
            id.drop(1),
            id.uppercase(),
            "$id ",
            "$id;id",
            "\$(id)",
            "$id\n",
        )
        for (value in bad) {
            assertFailsWith<IllegalArgumentException> { TvCommands.writeSecret(value, secret) }
            assertFailsWith<IllegalArgumentException> { TvCommands.proveSecret(value, nonce) }
            assertFailsWith<IllegalArgumentException> { TvCommands.forgetSecret(value) }
        }
        for (value in listOf(secret.drop(2), secret.uppercase(), "$secret ", "$secret;id", "\$(id)", "$secret\n")) {
            assertFailsWith<IllegalArgumentException> { TvCommands.writeSecret(id, value) }
        }
        for (value in listOf(nonce.drop(2), nonce.uppercase(), "$nonce ", "$nonce;id", "\$(id)", "$nonce\n")) {
            assertFailsWith<IllegalArgumentException> { TvCommands.proveSecret(id, value) }
        }
    }
}
