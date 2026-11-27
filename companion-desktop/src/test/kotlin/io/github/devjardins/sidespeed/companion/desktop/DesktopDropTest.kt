package io.github.devjardins.sidespeed.companion.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopDropTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun accepts_one_supported_app_file() {
        for (name in listOf("app.apk", "bundle.APKS", "game.xapk", "set.apkm", "split.zip")) {
            val f = tmp.newFile(name)
            assertEquals(f, DesktopDrop.singleInstallFile(listOf(f)))
        }
    }

    @Test
    fun rejects_unsupported_extensions_folders_and_several_files() {
        val apk = tmp.newFile("a.apk")
        assertNull(DesktopDrop.singleInstallFile(listOf(tmp.newFile("notes.txt"))))
        assertNull(DesktopDrop.singleInstallFile(listOf(tmp.newFile("noext"))))
        assertNull(DesktopDrop.singleInstallFile(listOf(tmp.newFolder("dir.apk"))))
        assertNull(DesktopDrop.singleInstallFile(listOf(apk, tmp.newFile("b.apk"))))
        assertNull(DesktopDrop.singleInstallFile(emptyList<Any>()))
        assertNull(DesktopDrop.singleInstallFile(listOf("a.apk")))
        assertNull(DesktopDrop.singleInstallFile(null))
    }

    @Test
    fun card_under_pointer_wins_when_it_can_take_an_install() {
        assertEquals(DropHit.Tv("a"), DesktopDrop.target("a", setOf("a", "b")))
        assertEquals(DropHit.Tv("b"), DesktopDrop.target("b", setOf("a", "b")))
    }

    @Test
    fun falls_back_to_single_installable_tv_outside_the_cards() {
        assertEquals(DropHit.Tv("b"), DesktopDrop.target(null, setOf("b")))
        // A card that cannot take an install still falls back to the one TV that can.
        assertEquals(DropHit.Tv("b"), DesktopDrop.target("a", setOf("b")))
    }

    @Test
    fun misses_with_several_tvs_outside_the_cards_or_none_installable() {
        assertEquals(DropHit.Miss, DesktopDrop.target(null, setOf("a", "b")))
        assertEquals(DropHit.Miss, DesktopDrop.target("c", setOf("a", "b")))
        assertEquals(DropHit.Miss, DesktopDrop.target(null, emptySet()))
        assertEquals(DropHit.Miss, DesktopDrop.target("a", emptySet()))
    }
}
