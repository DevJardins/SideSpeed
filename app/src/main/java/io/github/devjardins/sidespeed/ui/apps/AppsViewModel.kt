package io.github.devjardins.sidespeed.ui.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.devjardins.sidespeed.apps.AppEntry
import io.github.devjardins.sidespeed.apps.AppSource
import io.github.devjardins.sidespeed.apps.sortForPicker
import io.github.devjardins.sidespeed.compile.PackageName
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AppsUiState(val apps: List<AppEntry> = emptyList(), val loading: Boolean = true)

sealed interface ManualResult {
    data class Valid(val app: AppEntry) : ManualResult
    data object NotInstalled : ManualResult
    data object Invalid : ManualResult
}

class AppsViewModel(
    private val source: AppSource,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(AppsUiState())
    val state: StateFlow<AppsUiState> = _state.asStateFlow()

    /** Loads the app list on the injected IO dispatcher, never on Main. */
    fun load() {
        viewModelScope.launch(ioDispatcher) {
            val apps = sortForPicker(source.list())
            _state.value = AppsUiState(apps, loading = false)
        }
    }

    fun manualEntry(raw: String): ManualResult {
        val pkg = PackageName.parse(raw) ?: return ManualResult.Invalid
        return source.find(pkg.value)?.let { ManualResult.Valid(it) } ?: ManualResult.NotInstalled
    }

    /** Restores a selected app after process/config restore (package may no longer be installed). */
    fun manualEntryApp(pkg: String): AppEntry? = (manualEntry(pkg) as? ManualResult.Valid)?.app
}
