package io.github.devjardins.sidespeed.tools

import io.github.devjardins.sidespeed.helper.SideSpeedPackages

/** Packages that must never be disabled by Turn off extras (mirrors the import script Core set). */
object ExtrasSafety {
    private val exact = setOf(
        "android",
        "android.ext.services",
        "android.ext.shared",
        "com.android.sdksandbox",
        "com.android.location.fused",
        "com.android.systemui",
        "com.android.shell",
        "com.android.phone",
        "com.android.settings",
        "com.android.tv.settings",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.ext.services",
        "com.google.android.ext.services",
        "com.android.ext.shared",
        "com.google.android.ext.shared",
        "com.android.modulemetadata",
        "com.google.android.modulemetadata",
        "com.cltv.mal",
        "com.mediatek.tis",
        "com.mediatek.tv.service",
        "com.mediatek.tv.agent",
    ) + SideSpeedPackages.ALL

    private val providerOk = setOf(
        "com.android.providers.calendar",
        "com.android.providers.contacts",
        "com.android.providers.userdictionary",
        "com.android.providers.blockednumber",
    )

    fun isCore(pkg: String): Boolean {
        if (pkg in exact) return true
        if (pkg.startsWith("com.google.android.overlay.modules")) return true
        if (pkg.startsWith("com.android.providers.") && pkg !in providerOk) return true
        return false
    }
}