package io.github.devjardins.sidespeed.ui.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.devjardins.sidespeed.tools.ManualCheckResult
import io.github.devjardins.sidespeed.tools.UpdateCheck
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** About screen state: the manual check outcome and the update prompt's tag, when shown. */
data class AboutUiState(
    val checking: Boolean = false,
    val result: ManualCheckResult? = null,
    val promptTag: String? = null,
)

/**
 * Drives About's "Check for updates": a newer release opens the update prompt right away
 * ([AboutUiState.promptTag]); "Later" only hides it and never stores a dismissal, so the
 * Apps banner still governs that version. [startUpdate] is the shared [io.github.devjardins.sidespeed.update.SelfUpdater]
 * entry point, so the prompt and the banner run the exact same download/verify/install flow.
 * [debugNewerTag] mirrors the banner's debug hook: debuggable builds report that tag as
 * newer without a network fetch, so the prompt can be exercised on an emulator.
 */
class AboutViewModel(
    private val check: UpdateCheck,
    private val startUpdate: (String) -> Unit,
    private val debugNewerTag: String? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(AboutUiState())
    val state: StateFlow<AboutUiState> = _state.asStateFlow()

    fun checkNow() {
        if (_state.value.checking) return
        _state.update { it.copy(checking = true) }
        viewModelScope.launch(ioDispatcher) {
            val result = if (debugNewerTag != null) {
                ManualCheckResult.Newer(debugNewerTag)
            } else {
                check.checkNow()
            }
            _state.update {
                it.copy(
                    checking = false,
                    result = result,
                    promptTag = (result as? ManualCheckResult.Newer)?.tag,
                )
            }
        }
    }

    /** "Later" / Back: hide the prompt. Deliberately stores no dismissal. */
    fun dismissPrompt() {
        _state.update { it.copy(promptTag = null) }
    }

    /** "Update now" / Retry: start the shared update flow for the prompted tag. */
    fun onUpdateNow() {
        val tag = _state.value.promptTag ?: return
        startUpdate(tag)
    }
}
