package io.github.devjardins.sidespeed.compile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PackageNameTest {
    @Test fun acceptsValidNames() {
        assertEquals("com.nuvio.tv", PackageName.parse("com.nuvio.tv")?.value)
        assertNotNull(PackageName.parse("org.xbmc.kodi"))
    }

    @Test fun trimsBeforeValidating() {
        assertEquals("com.nuvio.tv", PackageName.parse("  com.nuvio.tv\n")?.value)
    }

    @Test fun rejectsInvalidNames() {
        listOf("nuvio", "com.foo; reboot", "com.foo bar", "com.foo'", "com.foo\$(id)", "", "1com.foo")
            .forEach { assertNull(it, PackageName.parse(it)) }
    }
}
