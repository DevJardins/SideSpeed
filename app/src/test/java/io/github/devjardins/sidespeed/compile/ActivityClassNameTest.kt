package io.github.devjardins.sidespeed.compile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActivityClassNameTest {
    @Test fun acceptsPlainAndNestedNames() {
        assertEquals("a.B", ActivityClassName.parse("a.B")?.value)
        assertEquals("a.B\$C", ActivityClassName.parse("a.B\$C")?.value)
        assertEquals("com.foo.Main\$Launcher", ActivityClassName.parse("com.foo.Main\$Launcher")?.value)
        assertEquals("_a.B_1\$Inner", ActivityClassName.parse("_a.B_1\$Inner")?.value)
        assertEquals("a.B\$C\$D", ActivityClassName.parse("a.B\$C\$D")?.value)
    }

    @Test fun acceptsMaxLength() {
        val max = "a." + "B".repeat(253)
        assertEquals(255, max.length)
        assertEquals(max, ActivityClassName.parse(max)?.value)
    }

    @Test fun rejectsInvalidNames() {
        val over = "a." + "B".repeat(254)
        assertEquals(256, over.length)
        listOf(
            "a.B\$",
            "\$a.B",
            "a..\$B",
            "a..B",
            "a.B\$\$C",
            "a.B;rm",
            "a.B c",
            "a/B",
            "a.B\n",
            "a.B\t",
            " com.foo.Main",
            "com.foo.Main ",
            "",
            "Main",
            ".Main",
            "com.foo.",
            "com.foo.Main\$1",
            "com.foo.\$Inner",
            "1a.B",
            over,
        ).forEach { assertNull(it, ActivityClassName.parse(it)) }
    }
}
