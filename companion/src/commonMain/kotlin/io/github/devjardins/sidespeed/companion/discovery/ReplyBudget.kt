package io.github.devjardins.sidespeed.companion.discovery

/** Caps how many UDP replies [SocketDatagramQuery] keeps during one listen window. */
class ReplyBudget(
    private val maxDatagrams: Int = MAX_DATAGRAMS,
    private val maxBytes: Int = MAX_DATAGRAM_BYTES,
) {
    private var count = 0
    private var bytes = 0
    private var ended = false

    /** True when another reply of [length] bytes may be kept; false when the cap is reached or this reply would exceed it. */
    fun accept(length: Int): Boolean {
        if (ended) return false
        if (count >= maxDatagrams) {
            ended = true
            return false
        }
        if (bytes + length > maxBytes) {
            ended = true
            return false
        }
        count++
        bytes += length
        return true
    }
}

const val MAX_DATAGRAMS = 256
const val MAX_DATAGRAM_BYTES = 1 shl 20