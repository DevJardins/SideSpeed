package io.github.devjardins.sidespeed.status

/** In-memory [CompileStatusStore] for tests. */
class FakeCompileStatusStore : CompileStatusStore {
    var saved: PersistedCompileStatus? = null
    var loads = 0
    override suspend fun load(): PersistedCompileStatus? {
        loads++
        return saved
    }
    override suspend fun save(value: PersistedCompileStatus?) {
        saved = value
    }
}
