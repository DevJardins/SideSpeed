package io.github.devjardins.sidespeed.companion.store

import io.github.devjardins.sidespeed.companion.net.NetworkFingerprint
import io.github.devjardins.sidespeed.companion.net.TrustedNetwork
import io.github.devjardins.sidespeed.companion.net.TrustedOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Keeps everything in one JSON file, [FILE_NAME], under [dir] (the platform data directory, supplied by the
 * caller). Each save rewrites the whole file atomically: a temp file in the same directory, then a rename.
 *
 * A missing, unreadable or corrupt file reads as empty defaults and is never rewritten by a read. The next
 * successful save moves a corrupt file aside to `<name>.corrupt` and writes fresh state. Individual entries that
 * are malformed (missing fields, blank serial) are skipped; an unknown status reads as [TvStatus.UNKNOWN].
 *
 * The file and its directory are owner-only (POSIX 0600 / 0700; best effort on Windows, whose per-user app-data
 * ACL already restricts access), and the temp file is fsynced before the rename.
 *
 * Versioning: the file carries a `version` number. A file saved by a newer copy (a greater version than
 * [VERSION]) loads what can be read but puts the store in a read-only "newer data" mode: every save is a
 * no-op that leaves the file byte-for-byte unchanged, and [savedByNewerVersion] is true so the UI can say
 * so. A file saved by an older copy (a lower version) is copied to `companion.json.v<old>.bak` next to the
 * file on the first save — keeping only the most recent backup — and then written at [VERSION]. A missing
 * or invalid `version` reads as 1.
 */
class JsonFileCompanionStore(private val dir: File) : CompanionStore {
    private data class State(
        val tvs: List<SavedTv> = emptyList(),
        val networks: List<TrustedNetwork> = emptyList(),
        val overlays: List<TrustedOverlay> = emptyList(),
        val settings: CompanionSettings = CompanionSettings(),
        val prompted: List<PromptedHost> = emptyList(),
    )

    private val mutex = Mutex()
    private var state: State? = null
    private var loadedCorrupt = false
    private var loadedFromFile = false
    private var loadedVersion: Long = VERSION.toLong()
    private var backupDone = false
    @Volatile private var readOnlyNewer = false

    override val savedByNewerVersion: Boolean
        get() {
            // The flag must answer even before the first read, so load synchronously here when nothing
            // has been read yet; later reads reuse the cached state. Internal saves use [readOnlyNewer]
            // directly and never take this path while holding [mutex].
            if (state == null) runBlocking { mutex.withLock { loaded() } }
            return readOnlyNewer
        }

    private val file get() = File(dir, FILE_NAME)

    override suspend fun tvs(): List<SavedTv> = read { it.tvs }
    override suspend fun trustedNetworks(): List<TrustedNetwork> = read { it.networks }
    override suspend fun settings(): CompanionSettings = read { it.settings }
    override suspend fun trustedOverlays(): List<TrustedOverlay> = read { it.overlays }

    override suspend fun saveTv(tv: SavedTv) {
        SavedTv.requireValid(tv)
        update { it.copy(tvs = it.tvs.upsert(tv) { x -> x.id }, prompted = emptyList()) }
    }

    override suspend fun updateTv(id: String, transform: (SavedTv) -> SavedTv): Boolean = mutex.withLock {
        val current = loaded()
        // Read-only "newer data" mode: report that nothing was saved and leave the file untouched.
        if (readOnlyNewer) return@withLock false
        val old = current.tvs.firstOrNull { it.id == id } ?: return@withLock false
        val new = transform(old)
        require(new.id == id) { "updateTv cannot change the id" }
        SavedTv.requireValid(new)
        backupIfNeeded()
        commit(current.copy(tvs = current.tvs.upsert(new) { x -> x.id }))
        loadedVersion = VERSION.toLong()
        true
    }

    override suspend fun promptedHosts(): List<PromptedHost> = read { it.prompted }

    override suspend fun savePromptedHosts(hosts: List<PromptedHost>) = update { it.copy(prompted = hosts) }

    override suspend fun removeTv(id: String) = update { it.copy(tvs = it.tvs.filterNot { t -> t.id == id }) }

    override suspend fun saveTrustedNetwork(n: TrustedNetwork) =
        update { it.copy(networks = it.networks.upsert(n) { x -> x.id }) }

    override suspend fun removeTrustedNetwork(id: String) =
        update { it.copy(networks = it.networks.filterNot { n -> n.id == id }) }

    override suspend fun saveTrustedOverlay(o: TrustedOverlay) =
        update { it.copy(overlays = it.overlays.upsert(o) { x -> x.id }) }

    override suspend fun removeTrustedOverlay(id: String) =
        update { it.copy(overlays = it.overlays.filterNot { o -> o.id == id }) }

    override suspend fun saveSettings(s: CompanionSettings) = update { it.copy(settings = s) }

    private suspend fun <T> read(pick: (State) -> T): T = mutex.withLock { pick(loaded()) }

    private suspend fun update(change: (State) -> State) = mutex.withLock {
        val current = loaded()
        // Read-only "newer data" mode: drop the change so the file stays byte-for-byte unchanged.
        if (readOnlyNewer) return@withLock
        backupIfNeeded()
        commit(change(current))
        loadedVersion = VERSION.toLong()
    }

    /** Caller holds [mutex]. */
    private suspend fun commit(next: State) {
        withContext(Dispatchers.IO) { writeAtomically(encode(next)) }
        state = next
    }

    private suspend fun loaded(): State = state ?: withContext(Dispatchers.IO) { load() }.also { state = it }

    private fun load(): State {
        val f = file
        if (!f.exists()) {
            loadedFromFile = false
            loadedVersion = VERSION.toLong()
            return State()
        }
        loadedFromFile = true
        return try {
            val root = MiniJson.parse(f.readText(Charsets.UTF_8))
            loadedVersion = (root as? Map<*, *>)?.fileVersion() ?: 1L
            readOnlyNewer = loadedVersion > VERSION
            decode(root) ?: throw MiniJson.Malformed("Not a store object")
        } catch (e: MiniJson.Malformed) {
            loadedCorrupt = true
            State()
        } catch (e: IOException) {
            // Unreadable: same as corrupt, so it is set aside rather than lost on the next save.
            loadedCorrupt = true
            State()
        }
    }

    /** A missing, non-integer or out-of-range `version` reads as 1, the original schema. */
    private fun Map<*, *>.fileVersion(): Long {
        val v = this["version"] as? Long ?: return 1L
        return if (v < 1L) 1L else v
    }

    /**
     * Caller holds [mutex]. Before the first save that upgrades an older file, copies the original
     * file to `companion.json.v<old>.bak` next to it, deleting any other versioned backup so only the
     * most recent one is kept. Corrupt or unreadable files keep their `.corrupt` handling instead.
     */
    private fun backupIfNeeded() {
        if (backupDone || loadedCorrupt || !loadedFromFile || loadedVersion >= VERSION) return
        val bakName = "$FILE_NAME.v$loadedVersion.bak"
        dir.listFiles { f -> f.isFile && f.name.startsWith("$FILE_NAME.v") && f.name.endsWith(".bak") && f.name != bakName }
            ?.forEach { it.delete() }
        if (!file.exists()) {
            backupDone = true
            return
        }
        val bak = File(dir, bakName)
        Files.copy(file.toPath(), bak.toPath(), StandardCopyOption.REPLACE_EXISTING)
        OwnerOnly.restrict(bak, directory = false)
        backupDone = true
    }

    private fun writeAtomically(text: String) {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("Cannot create $dir")
        OwnerOnly.restrict(dir, directory = true)
        val target = file.toPath()
        if (loadedCorrupt && file.exists()) {
            Files.move(target, File(dir, "$FILE_NAME.corrupt").toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        val tmp = File.createTempFile(FILE_NAME, ".tmp", dir)
        try {
            OwnerOnly.restrict(tmp, directory = false)
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync() // on disk before the rename makes it the store
            }
            try {
                Files.move(tmp.toPath(), target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            tmp.delete()
        }
        loadedCorrupt = false
    }

    private fun <T> List<T>.upsert(item: T, id: (T) -> String): List<T> {
        val i = indexOfFirst { id(it) == id(item) }
        return if (i >= 0) toMutableList().also { it[i] = item } else this + item
    }

    // --- Encoding ---------------------------------------------------------------------------------------------

    private fun encode(s: State): String = MiniJson.write(
        mapOf(
            "version" to VERSION,
            "tvs" to s.tvs.map { t ->
                mapOf(
                    "id" to t.id, "name" to t.name, "address" to t.address, "serial" to t.serial,
                    "trustedNetworkId" to t.trustedNetworkId, "status" to t.status.name,
                    "lastCheckedEpochMs" to t.lastCheckedEpochMs, "offlineCount" to t.offlineCount,
                    "lastRefindEpochMs" to t.lastRefindEpochMs,
                    "skipBackgroundUntilUserAction" to t.skipBackgroundUntilUserAction,
                    "isFireTv" to t.isFireTv,
                    "overlayPeerId" to t.overlayPeerId,
                    "reach" to t.reach?.name,
                )
            },
            "trustedNetworks" to s.networks.map { n ->
                mapOf(
                    "id" to n.id, "name" to n.name,
                    "fingerprint" to mapOf(
                        "gatewayIp" to n.fingerprint.gatewayIp,
                        "subnet" to n.fingerprint.subnet,
                        "dnsServers" to n.fingerprint.dnsServers.toList(),
                        "gatewayMac" to n.fingerprint.gatewayMac,
                        "wifiSsid" to n.fingerprint.wifiSsid,
                        "wifiBssid" to n.fingerprint.wifiBssid,
                    ),
                )
            },
            "trustedOverlays" to s.overlays.map { o ->
                mapOf("id" to o.id, "name" to o.name, "tailnet" to o.tailnet, "selfId" to o.selfId)
            },
            "promptedHosts" to s.prompted.map { p ->
                mapOf("trustedNetworkId" to p.trustedNetworkId, "address" to p.address, "atEpochMs" to p.atEpochMs)
            },
            "settings" to mapOf(
                "notifications" to s.settings.notifications,
                "intervalMinutes" to s.settings.intervalMinutes,
                "startAtLogin" to s.settings.startAtLogin,
                "useWifiName" to s.settings.useWifiName,
            ),
        ),
    )

    private fun decode(root: Any?): State? {
        if (root !is Map<*, *>) return null
        val tvs = (root["tvs"] as? List<*>).orEmpty().mapNotNull { decodeTv(it) }
        val networks = (root["trustedNetworks"] as? List<*>).orEmpty().mapNotNull { decodeNetwork(it) }
        val overlays = (root["trustedOverlays"] as? List<*>).orEmpty().mapNotNull { decodeOverlay(it) }
        val defaults = CompanionSettings()
        val settings = (root["settings"] as? Map<*, *>)?.let { m ->
            CompanionSettings(
                notifications = m["notifications"] as? Boolean ?: defaults.notifications,
                intervalMinutes = m.int("intervalMinutes")?.takeIf { it > 0 } ?: defaults.intervalMinutes,
                startAtLogin = m["startAtLogin"] as? Boolean ?: defaults.startAtLogin,
                useWifiName = m["useWifiName"] as? Boolean ?: defaults.useWifiName,
            )
        } ?: defaults
        val prompted = (root["promptedHosts"] as? List<*>).orEmpty().mapNotNull { v ->
            val m = v as? Map<*, *> ?: return@mapNotNull null
            PromptedHost(
                trustedNetworkId = m["trustedNetworkId"] as? String ?: return@mapNotNull null,
                address = m["address"] as? String ?: return@mapNotNull null,
                atEpochMs = m.long("atEpochMs") ?: return@mapNotNull null,
            )
        }
        return State(tvs, networks, overlays, settings, prompted)
    }

    private fun decodeTv(v: Any?): SavedTv? {
        val m = v as? Map<*, *> ?: return null
        val tv = SavedTv(
            id = m["id"] as? String ?: return null,
            name = m["name"] as? String ?: return null,
            address = m["address"] as? String ?: return null,
            serial = m["serial"] as? String ?: return null,
            trustedNetworkId = m["trustedNetworkId"] as? String ?: return null,
            status = TvStatus.entries.firstOrNull { it.name == m["status"] } ?: TvStatus.UNKNOWN,
            lastCheckedEpochMs = m.long("lastCheckedEpochMs"),
            offlineCount = m.int("offlineCount")?.coerceAtLeast(0) ?: 0,
            lastRefindEpochMs = m.long("lastRefindEpochMs"),
            skipBackgroundUntilUserAction = m["skipBackgroundUntilUserAction"] as? Boolean ?: false,
            isFireTv = m["isFireTv"] as? Boolean ?: true,
            overlayPeerId = (m["overlayPeerId"] as? String)?.takeIf { it.isNotEmpty() },
            reach = (m["reach"] as? String)?.let { name -> Reach.entries.firstOrNull { it.name == name } },
        )
        return if (tv.id.isBlank() || tv.serial.isBlank()) null else tv
    }

    private fun decodeOverlay(v: Any?): TrustedOverlay? {
        val m = v as? Map<*, *> ?: return null
        val o = TrustedOverlay(
            id = m["id"] as? String ?: return null,
            name = m["name"] as? String ?: return null,
            tailnet = m["tailnet"] as? String ?: return null,
            selfId = m["selfId"] as? String ?: return null,
        )
        // A blank identity could never match, so keeping it would only be a dead entry.
        return if (o.id.isBlank() || o.tailnet.isBlank() || o.selfId.isBlank()) null else o
    }

    private fun decodeNetwork(v: Any?): TrustedNetwork? {
        val m = v as? Map<*, *> ?: return null
        val fp = m["fingerprint"] as? Map<*, *> ?: return null
        return TrustedNetwork(
            id = m["id"] as? String ?: return null,
            name = m["name"] as? String ?: return null,
            fingerprint = NetworkFingerprint(
                gatewayIp = fp["gatewayIp"] as? String ?: return null,
                subnet = fp["subnet"] as? String ?: return null,
                dnsServers = (fp["dnsServers"] as? List<*>)?.map { it as? String ?: return null }?.toSet() ?: return null,
                gatewayMac = fp["gatewayMac"] as? String,
                wifiSsid = fp["wifiSsid"] as? String,
                wifiBssid = fp["wifiBssid"] as? String,
            ),
        )
    }

    private fun Map<*, *>.long(key: String): Long? = this[key] as? Long

    private fun Map<*, *>.int(key: String): Int? =
        (this[key] as? Long)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

    companion object {
        const val FILE_NAME = "companion.json"

        /**
         * The schema version this build writes. Bump it whenever a saved field is added or a saved
         * field's meaning changes, so older builds go read-only instead of dropping what they don't
         * know. History: v1 was the original schema; v2 added `trustedOverlays`, the TV's
         * `overlayPeerId` binding, and `reach`; v3 added the fingerprint's `wifiSsid`/`wifiBssid` and
         * the settings' `useWifiName`.
         */
        private const val VERSION = 3
    }
}
