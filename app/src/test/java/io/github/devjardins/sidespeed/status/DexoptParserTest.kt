package io.github.devjardins.sidespeed.status

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DexoptParserTest {
    private fun sample(name: String) =
        checkNotNull(javaClass.classLoader!!.getResource("dumpsys/$name")) { name }.readText()

    @Test fun androidN() = assertEquals("speed", DexoptParser.parse(sample("aosp-n-dexopt.txt"), "com.nuvio.tv").filter)

    @Test fun androidP() =
        assertEquals("speed-profile", DexoptParser.parse(sample("aosp-p-dexopt.txt"), "com.nuvio.tv").filter)

    @Test fun androidS() = assertEquals("speed", DexoptParser.parse(sample("aosp-s-dexopt.txt"), "com.nuvio.tv").filter)

    @Test fun verifyCase() =
        assertEquals("verify", DexoptParser.parse(sample("aosp-s-dexopt.txt"), "com.nuvio.tv.beta").filter)

    @Test fun lookalikeNotMatchedEitherWay() {
        assertNull(DexoptParser.parse("Dexopt state:\n  [com.nuvio.tv.beta]\n    path: x\n      arm64: [status=speed]\n", "com.nuvio.tv").filter)
        assertNull(DexoptParser.parse("Dexopt state:\n  [com.nuvio.tv]\n    path: x\n      arm64: [status=speed]\n", "com.nuvio.tv.beta").filter)
    }

    @Test fun mixedAbiGivesNeedsRecompile() {
        val dump = "  [a.b]\n    path: x\n      arm64: [status=speed] [reason=cmdline]\n      arm: [status=verify] [reason=install]\n"
        val status = DexoptParser.parse(dump, "a.b")
        assertEquals("verify", status.filter)
        assertEquals(StatusBadge.NEEDS_RECOMPILE, DexoptParser.badge(status))
    }

    @Test fun secondaryDexIgnored() {
        val dump = "  [a.b]\n    path: x\n      arm64: [status=verify]\n    known secondary dex files:\n      /y.dex\n        arm64: [status=speed]\n"
        val status = DexoptParser.parse(dump, "a.b")
        assertEquals("verify", status.filter)
        assertEquals(StatusBadge.NEEDS_RECOMPILE, DexoptParser.badge(status))
    }

    @Test fun emptyDumpIsUnknown() {
        val status = DexoptParser.parse("", "x.y")
        assertNull(status.filter)
        assertEquals(StatusBadge.UNKNOWN, DexoptParser.badge(status))
    }

    @Test fun badgeMapping() {
        assertEquals(StatusBadge.SPEED_OK, DexoptParser.badge(CompilerStatus("speed")))
        assertEquals(StatusBadge.SPEED_PROFILE, DexoptParser.badge(CompilerStatus("speed-profile")))
        assertEquals(StatusBadge.NEEDS_RECOMPILE, DexoptParser.badge(CompilerStatus("run-from-apk")))
    }
}
