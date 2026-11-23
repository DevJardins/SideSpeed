package io.github.devjardins.sidespeed.companion.ui

/** Lets the shared UI ask for the Wi-Fi name without knowing the platform's permission flow. */
interface WifiNameAccess {
    /** Whether this platform can read the Wi-Fi name at all (Android only; desktop never prompts). */
    val supported: Boolean

    /** Whether reading now would return a real name (the permission is granted). */
    fun granted(): Boolean

    /** Asks the user; true when they granted it. Always false when unsupported. */
    suspend fun request(): Boolean

    companion object {
        val Unsupported: WifiNameAccess = object : WifiNameAccess {
            override val supported = false
            override fun granted() = false
            override suspend fun request() = false
        }
    }
}
