package io.github.devjardins.sidespeed.apps

val STORE_INSTALLERS = setOf("com.android.vending", "com.amazon.venezia")

data class AppEntry(
    val packageName: String,
    val label: String,
    val installer: String?,
    val lastUpdateTime: Long,
) {
    /** True unless installed by a known store; an unknown (null) installer counts as sideloaded. */
    val isSideloaded: Boolean get() = installer !in STORE_INSTALLERS
}

/** Sideloaded apps first, then by label (case-insensitive). */
fun sortForPicker(apps: List<AppEntry>): List<AppEntry> =
    apps.sortedWith(
        compareBy<AppEntry> { !it.isSideloaded }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label },
    )
