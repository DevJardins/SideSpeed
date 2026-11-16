package io.github.devjardins.sidespeed.companion.net

/** Reads the current network's fingerprint. Null means there is no usable network. */
fun interface FingerprintSource {
    suspend fun current(): NetworkFingerprint?
}
