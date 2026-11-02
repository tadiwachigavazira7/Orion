// ============================================================
// app/FindFlowViewModel.kt   — enforces resolve-before-navigate structurally,
// and refuses/cancels RFID work whenever device access is not granted.
// ============================================================
package com.orion.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orion.core.enrollment.DeviceAccessGate
import com.orion.core.enrollment.grantsAccess
import com.orion.core.inventory.EpcTarget
import com.orion.core.inventory.FindInput
import com.orion.core.inventory.ResolveResult
import com.orion.core.inventory.ResolveTargetUseCase
import com.orion.core.session.FindTagUseCase
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    /** This device is not currently authorized; no lookup or RFID work may run. */
    data object AccessBlocked : FindUiState
}

class FindFlowViewModel(
    private val resolveTarget: ResolveTargetUseCase,
    private val findTag: FindTagUseCase,
    private val accessGate: DeviceAccessGate
) : ViewModel() {

    private val _state = MutableStateFlow<FindUiState>(FindUiState.Idle)
    val state: StateFlow<FindUiState> = _state.asStateFlow()

    // All mutated on the main thread only (viewModelScope / UI callbacks).
    private var navigationJob: Job? = null
    private var warmUpJob: Job? = null
    private var lookupJob: Job? = null
    /** Barrier after a block: completes once the cancelled jobs' teardowns have finished. */
    private var teardownJob: Job? = null

    init {
        viewModelScope.launch {
            accessGate.state
                .map { it.grantsAccess }
                .distinctUntilChanged()
                .collect { granted ->
                    if (!granted) {
                        blockAccess()
                    } else if (_state.value is FindUiState.AccessBlocked) {
                        _state.value = FindUiState.Idle
                    }
                }
        }
    }

    /**
     * Warms the reader. The warm-up job OWNS the warm reader for its whole lifetime: it
     * stays alive (awaitCancellation) and releases the reader in its own NonCancellable
     * finally block, so cancellation from blockAccess() or viewModelScope (ViewModel
     * cleared) releases it structurally, and always after warm-up's own connect has
     * stopped (same coroutine), so a late warm-up can never reconnect after the release.
     */
    fun onResolutionScreenOpened() {
        if (!ensureAccess()) return
        val previousWarmUp = warmUpJob
        val previousTeardown = teardownJob
        previousWarmUp?.cancel()
        warmUpJob = viewModelScope.launch {
            previousTeardown?.join()
            previousWarmUp?.join()
            try {
                findTag.warmUp()
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { findTag.release() }
            }
        }
    }

    /** Scanned or typed EPC → validate → navigate to the single target. */
    fun onFind(input: FindInput) {
        if (!ensureAccess()) return
        lookupJob?.cancel()
        lookupJob = viewModelScope.launch {
            _state.value = FindUiState.Resolving
            when (val r = resolveTarget.resolve(input)) {
                is ResolveResult.Resolved -> startNavigation(r.target)
                is ResolveResult.NotFound -> _state.value = FindUiState.NotFound(r.epc)
                is ResolveResult.Invalid  -> _state.value = FindUiState.Invalid(r.reason)
                is ResolveResult.Failure  -> _state.value = FindUiState.Error(r.reason)
            }
        }
    }

    /** Search path: show candidate EPCs, associate picks one. */
    fun onSearch(query: String) {
        if (!ensureAccess()) return
        lookupJob?.cancel()
        lookupJob = viewModelScope.launch {
            _state.value = FindUiState.Resolving
            val candidates = resolveTarget.search(query)
            _state.value = if (candidates.isEmpty()) FindUiState.NotFound(query)
                           else FindUiState.PickEpc(candidates)
        }
    }

    fun onEpcChosen(target: EpcTarget) = startNavigation(target)

    /**
     * Reached ONLY with a resolved EPC — the structural gate. Re-checks device
     * access here (after resolution may have suspended) so navigation can never
     * start while blocked.
     */
    private fun startNavigation(target: EpcTarget) {
        if (!ensureAccess()) return
        val targetName = target.displayName ?: target.epc
        val previousNavigation = navigationJob
        val previousTeardown = teardownJob
        previousNavigation?.cancel()
        _state.value = FindUiState.Navigating(CompassUiState.Searching, targetName)
        navigationJob = viewModelScope.launch {
            // Never connect while a previous session's teardown is still disconnecting.
            previousTeardown?.join()
            previousNavigation?.join()
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

    private fun ensureAccess(): Boolean {
        if (accessGate.isAccessGranted()) return true
        blockAccess()
        return false
    }

    /**
     * Cancels the active RFID session and any pending lookup. Each job owns its own
     * NonCancellable teardown (navigation: FindTagUseCase.find's onCompletion; warm-up:
     * its finally block), so blocking only cancels them; [teardownJob] is a barrier that
     * completes once those teardowns have finished, and new warm-up/navigation waits on
     * it before touching the reader.
     */
    private fun blockAccess() {
        _state.value = FindUiState.AccessBlocked
        val navigation = navigationJob.also { navigationJob = null }
        val warmUp = warmUpJob.also { warmUpJob = null }
        lookupJob?.cancel()
        lookupJob = null
        navigation?.cancel()
        warmUp?.cancel()
        if (navigation == null && warmUp == null) return
        val previousTeardown = teardownJob
        // UNDISPATCHED + NonCancellable: the barrier can't be cancelled before it starts.
        teardownJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                previousTeardown?.join()
                navigation?.join()
                warmUp?.join()
            }
        }
    }
}
