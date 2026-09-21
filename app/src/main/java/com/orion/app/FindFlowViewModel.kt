// ============================================================
// app/FindFlowViewModel.kt   — enforces resolve-before-navigate structurally
// ============================================================
package com.orion.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orion.core.inventory.EpcTarget
import com.orion.core.inventory.FindInput
import com.orion.core.inventory.ResolveResult
import com.orion.core.inventory.ResolveTargetUseCase
import com.orion.core.session.FindTagUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface FindUiState {
    data object Idle : FindUiState
    data object Resolving : FindUiState
    data class PickEpc(val candidates: List<EpcTarget>) : FindUiState  // only from search
    data class NotFound(val epc: String) : FindUiState
    data class Invalid(val reason: String) : FindUiState
    data class Navigating(val compass: CompassUiState, val targetName: String) : FindUiState
    data class Error(val message: String) : FindUiState
}

class FindFlowViewModel(
    private val resolveTarget: ResolveTargetUseCase,
    private val findTag: FindTagUseCase
) : ViewModel() {

    private val _state = MutableStateFlow<FindUiState>(FindUiState.Idle)
    val state: StateFlow<FindUiState> = _state.asStateFlow()

    /**
     * Warms the reader. Joins any cancelled navigation session first so this connect cannot
     * run before that session's reader teardown (stopInventory/disconnect) has finished.
     */
    fun onResolutionScreenOpened() = viewModelScope.launch {
        navigationJob?.join()
        findTag.warmUp()
    }

    /** Single in-flight resolution (typed EPC or search); replaced on each request, cancelled by [back]. */
    private var resolveJob: Job? = null

    /** Scanned or typed EPC → validate → navigate to the single target. */
    fun onFind(input: FindInput): Job {
        resolveJob?.cancel()
        _state.value = FindUiState.Resolving
        return viewModelScope.launch {
            when (val r = resolveTarget.resolve(input)) {
                is ResolveResult.Resolved -> startNavigation(r.target)
                is ResolveResult.NotFound -> _state.value = FindUiState.NotFound(r.epc)
                is ResolveResult.Invalid  -> _state.value = FindUiState.Invalid(r.reason)
                is ResolveResult.Failure  -> _state.value = FindUiState.Error(r.reason)
            }
        }.also { resolveJob = it }
    }

    /** Search path: show candidate EPCs, associate picks one. */
    fun onSearch(query: String): Job {
        resolveJob?.cancel()
        _state.value = FindUiState.Resolving
        return viewModelScope.launch {
            val candidates = resolveTarget.search(query)
            _state.value = if (candidates.isEmpty()) FindUiState.NotFound(query)
                           else FindUiState.PickEpc(candidates)
        }.also { resolveJob = it }
    }

    fun onEpcChosen(target: EpcTarget) = startNavigation(target)

    private var navigationJob: Job? = null

    /** Reached ONLY with a resolved EPC — the structural gate. */
    private fun startNavigation(target: EpcTarget) {
        val targetName = target.displayName ?: target.epc
        val previous = navigationJob
        previous?.cancel()
        _state.value = FindUiState.Navigating(CompassUiState.Searching, targetName)
        navigationJob = viewModelScope.launch {
            // Wait for the predecessor's reader teardown before connecting. The wait is
            // NonCancellable so a newer session cancelling this one mid-wait cannot skip it:
            // the newer session joins this job, which only ends after the predecessor's
            // teardown, so the newest session transitively waits for ALL earlier teardowns.
            withContext(NonCancellable) { previous?.cancelAndJoin() }
            // If cancelled during the wait (back / newer search / ViewModel cleared), exit
            // without connecting.
            ensureActive()
            findTag.find(target.epc)          // ← interpretation pipeline triggers here
                .catch {
                    // A failure here happens AFTER the compass screen has mounted (reader
                    // connect/startInventory failure, or an unexpected mid-stream exception),
                    // so it's rendered inside the compass screen with target context rather
                    // than collapsing back to the top-level FindUiState.Error used for
                    // pre-navigation resolution failures.
                    _state.value = FindUiState.Navigating(
                        CompassUiState.Error(it.message ?: "navigation failed"),
                        targetName
                    )
                }
                .collect { _state.value = FindUiState.Navigating(it.toCompassUiState(), targetName) }
        }
    }

    /**
     * Leave the compass screen (any state) and return to a fresh EPC entry screen.
     * Cancels the active navigation job (its teardown stops inventory and disconnects the
     * reader) and drops the previous target/candidates/messages by returning to Idle.
     * Only ever moves toward Idle, so it cannot start interpretation.
     */
    fun back() {
        resolveJob?.cancel()
        resolveJob = null
        // Keep the cancelled job: the next startNavigation/warmUp joins it so the old
        // session's reader teardown finishes before anything reconnects.
        navigationJob?.cancel()
        _state.value = FindUiState.Idle
    }
}
