package io.github.devjardins.sidespeed.update

import io.github.devjardins.sidespeed.R
import io.github.devjardins.sidespeed.testing.english
import io.github.devjardins.sidespeed.ui.text.UiText
import org.junit.Assert.assertEquals
import org.junit.Test

class FallbackInstallerTest {
    @Test fun below_26_installs() {
        assertEquals(FallbackAction.Install, fallbackAction(24, false))
        assertEquals(FallbackAction.Install, fallbackAction(25, true))
    }

    @Test fun api26_without_permission_opens_settings() {
        assertEquals(FallbackAction.OpenSettings, fallbackAction(26, false))
        assertEquals(FallbackAction.OpenSettings, fallbackAction(28, false))
        assertEquals(FallbackAction.OpenSettings, fallbackAction(36, false))
    }

    @Test fun api26_with_permission_installs() {
        assertEquals(FallbackAction.Install, fallbackAction(26, true))
        assertEquals(FallbackAction.Install, fallbackAction(36, true))
    }

    @Test fun settings_message_names_the_flows_retry_button() {
        assertEquals(
            "Allow SideSpeed to install apps, then press Retry.",
            unknownSourcesMessage(UiText.Res(R.string.update_banner_retry)).english(),
        )
        assertEquals(
            "Allow SideSpeed to install apps, then press Try again.",
            unknownSourcesMessage(UiText.Res(R.string.compile_update_try_again)).english(),
        )
    }
}
