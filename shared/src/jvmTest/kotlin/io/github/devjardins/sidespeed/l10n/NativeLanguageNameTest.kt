package io.github.devjardins.sidespeed.l10n

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeLanguageNameTest {
  @Test
  fun native_names() {
    assertEquals("Français", nativeLanguageName("fr"))
    assertEquals("العربية", nativeLanguageName("ar"))
    assertEquals("English", nativeLanguageName("en"))
    assertEquals("Português (Brasil)", nativeLanguageName("pt-BR"))
  }
}