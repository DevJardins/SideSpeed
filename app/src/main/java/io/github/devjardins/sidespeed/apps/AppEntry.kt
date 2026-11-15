package io.github.devjardins.sidespeed.apps

val STORE_INSTALLERS = setOf("com.android.vending", "com.amazon.venezia")

data class AppEntry(
    val packageName: String,
    val label: String,
    val installer: String?,
    val lastUpdateTime: Long,
    /** Preinstalled (FLAG_SYSTEM or FLAG_UPDATED_SYSTEM_APP); such apps often report a null installer. */
    val isSystem: Boolean = false,
) {
    /**
     * True for non-system apps not installed by a known store; an unknown (null) installer counts as sideloaded.
     * System apps never count, since preinstalled Amazon/OEM apps have a null installer too.
     */
    val isSideloaded: Boolean get() = !isSystem && installer !in STORE_INSTALLERS
}

/** Sideloaded apps first, then by label (case-insensitive). */
fun sortForPicker(apps: List<AppEntry>): List<AppEntry> =
    apps.sortedWith(
        compareBy<AppEntry> { !it.isSideloaded }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label },
    )

/** Where the picker gets its apps; lets view models be tested without a PackageManager. */
interface AppSource {
    fun list(): List<AppEntry>
    fun find(pkg: String): AppEntry?
}
