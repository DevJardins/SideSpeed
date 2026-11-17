package io.github.devjardins.sidespeed.maintenance

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

open class FakeMaintenanceStore : MaintenanceStore {
    val maintainedFlow = MutableStateFlow<Set<String>>(emptySet())
    val modeFlow = MutableStateFlow(AutoOptimizeMode.SELECTED)
    val historyFlow = MutableStateFlow<List<JobRecord>>(emptyList())

    override val maintained: Flow<Set<String>> = maintainedFlow
    override suspend fun setMaintained(pkg: String, on: Boolean) {
        maintainedFlow.value = if (on) maintainedFlow.value + pkg else maintainedFlow.value - pkg
    }
    override val mode: Flow<AutoOptimizeMode> = modeFlow
    override suspend fun setMode(mode: AutoOptimizeMode) { modeFlow.value = mode }
    override val history: Flow<List<JobRecord>> = historyFlow
    override suspend fun addHistory(record: JobRecord) { historyFlow.value = appendHistory(historyFlow.value, record) }
}
