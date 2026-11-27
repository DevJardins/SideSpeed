package io.github.devjardins.sidespeed.tools

import io.github.devjardins.sidespeed.helper.SideSpeedPackages
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtrasSafetyTest {
    @Test fun coreExactPackages() {
        assertTrue(ExtrasSafety.isCore("android"))
        assertTrue(ExtrasSafety.isCore("com.android.systemui"))
        assertTrue(ExtrasSafety.isCore(SideSpeedPackages.RELEASE))
        assertTrue(ExtrasSafety.isCore(SideSpeedPackages.DEBUG))
    }

    @Test fun overlayPrefix() {
        assertTrue(ExtrasSafety.isCore("com.google.android.overlay.modules.foo"))
    }

    @Test fun providerPrefixExceptAllowed() {
        assertTrue(ExtrasSafety.isCore("com.android.providers.media"))
        assertFalse(ExtrasSafety.isCore("com.android.providers.calendar"))
        assertFalse(ExtrasSafety.isCore("com.android.providers.contacts"))
        assertFalse(ExtrasSafety.isCore("com.android.providers.userdictionary"))
        assertFalse(ExtrasSafety.isCore("com.android.providers.blockednumber"))
    }

    @Test fun ordinaryPackageNotCore() {
        assertFalse(ExtrasSafety.isCore("com.example.bloat"))
    }
}