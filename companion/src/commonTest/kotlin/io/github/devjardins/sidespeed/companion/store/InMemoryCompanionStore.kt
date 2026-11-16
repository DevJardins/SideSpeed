package io.github.devjardins.sidespeed.companion.store

import io.github.devjardins.sidespeed.companion.net.TrustedNetwork

/** A [CompanionStore] for tests. Applies the same blank-serial rule as the file store. */
class InMemoryCompanionStore(
    tvs: List<SavedTv> = emptyList(),
    networks: List<TrustedNetwork> = emptyList(),
    private var settings: CompanionSettings = CompanionSettings(),
) : CompanionStore {
    private val tvs = tvs.toMutableList()
    private val networks = networks.toMutableList()
    var prompted: List<PromptedHost> = emptyList()

    fun tv(id: String): SavedTv = tvs.first { it.id == id }

    override suspend fun tvs(): List<SavedTv> = tvs.toList()
    override suspend fun saveTv(tv: SavedTv) {
        SavedTv.requireValid(tv)
        val i = tvs.indexOfFirst { it.id == tv.id }
        if (i >= 0) tvs[i] = tv else tvs += tv
        prompted = emptyList()
    }
    override suspend fun updateTv(id: String, transform: (SavedTv) -> SavedTv): Boolean {
        val i = tvs.indexOfFirst { it.id == id }
        if (i < 0) return false
        val new = transform(tvs[i])
        require(new.id == id)
        SavedTv.requireValid(new)
        tvs[i] = new
        return true
    }
    override suspend fun promptedHosts(): List<PromptedHost> = prompted
    override suspend fun savePromptedHosts(hosts: List<PromptedHost>) { prompted = hosts.toList() }
    override suspend fun removeTv(id: String) { tvs.removeAll { it.id == id } }
    override suspend fun trustedNetworks(): List<TrustedNetwork> = networks.toList()
    override suspend fun saveTrustedNetwork(n: TrustedNetwork) {
        val i = networks.indexOfFirst { it.id == n.id }
        if (i >= 0) networks[i] = n else networks += n
    }
    override suspend fun removeTrustedNetwork(id: String) { networks.removeAll { it.id == id } }
    override suspend fun settings(): CompanionSettings = settings
    override suspend fun saveSettings(s: CompanionSettings) { settings = s }
}
