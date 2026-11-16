package io.github.devjardins.sidespeed.maintenance

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

open class FakeMaintenanceStore : MaintenanceStore {
    val maintainedFlow = MutableStateFlow<Set<String>>(emptySet())
    val optimizeFlow = MutableStateFlow(false)
    val historyFlow = MutableStateFlow<List<JobRecord>>(emptyList())

    override val maintained: Flow<Set<String>> = maintainedFlow
    override suspend fun setMaintained(pkg: String, on: Boolean) {
        maintainedFlow.value = if (on) maintainedFlow.value + pkg else maintainedFlow.value - pkg
    }
    override val optimizeSideloaded: Flow<Boolean> = optimizeFlow
    override suspend fun setOptimizeSideloaded(on: Boolean) { optimizeFlow.value = on }
    override val history: Flow<List<JobRecord>> = historyFlow
    override suspend fun addHistory(record: JobRecord) { historyFlow.value = appendHistory(historyFlow.value, record) }
}
