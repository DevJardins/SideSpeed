package io.github.devjardins.sidespeed.companion.discovery

import com.flyfishxu.kadb.mdns.KadbMdns
import com.flyfishxu.kadb.mdns.MdnsServiceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One kadb-mdns browser for the process.
 *
 * The first lookup starts it. Later lookups read the services it already has, so a lookup never
 * builds and closes its own JmDNS. After [idleMs] with no lookup, [stopScope] closes it.
 * [KadbMdns.stop] blocks inside JmDNS until cancel announcements finish, so that close stays on
 * [stopScope] and the lookup that noticed the idle time does not wait for it.
 * A lookup that arrives while the close is still running starts a new browser.
 */
class SharedMdnsBrowser(
    private val open: suspend () -> KadbMdns,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idleMs: Long = IDLE_MS,
    private val stopScope: CoroutineScope,
    private val pollMs: Long = POLL_MS,
) {
    private val mutex = Mutex()
    private var current: Session? = null
    private var watcher: Job? = null

    private class Session(val mdns: KadbMdns, var lastUse: Long)

    /** The services the live browser has right now. Starts one on the first call. */
    suspend fun services(): List<TlsConnectService> {
        val mdns = mutex.withLock {
            val now = clock()
            val live = current
            if (live != null) {
                live.lastUse = now
                live.mdns
            } else {
                val created = open()
                created.start()
                current = Session(created, now)
                created
            }
        }
        ensureWatcher()
        return mdns.state.value.connectDevices
            .filter { it.serviceType == MdnsServiceType.TLS_CONNECT }
            .map { TlsConnectService(it.host, it.port, it.name) }
    }

    private suspend fun ensureWatcher() {
        mutex.withLock {
            if (watcher?.isActive == true) return
            watcher = stopScope.launch { watch() }
        }
    }

    private suspend fun watch() {
        while (true) {
            val retiring = mutex.withLock {
                val session = current ?: return@withLock null
                if (clock() - session.lastUse < idleMs) {
                    null
                } else {
                    current = null
                    session
                }
            }
            if (retiring != null) {
                try {
                    retiring.mdns.stop()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The browser is already dropped. The next lookup opens another.
                }
            }
            delay(pollMs)
        }
    }

    companion object {
        /** How long the browser stays up after the last lookup. */
        const val IDLE_MS = 60_000L
        /** How often the idle watcher reads [clock]. */
        const val POLL_MS = 1_000L
    }
}
