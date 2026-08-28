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
import com.orion.core.navigation.NavigationState
import com.orion.core.session.FindTagUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

sealed interface FindUiState {
    data object Idle : FindUiState
    data object Resolving : FindUiState
    data class PickEpc(val candidates: List<EpcTarget>) : FindUiState  // only from search
    data class NotFound(val epc: String) : FindUiState
    data class Invalid(val reason: String) : FindUiState
    data class Navigating(val nav: NavigationState) : FindUiState
    data class Error(val message: String) : FindUiState
}

class FindFlowViewModel(
    private val resolveTarget: ResolveTargetUseCase,
    private val findTag: FindTagUseCase
) : ViewModel() {

    private val _state = MutableStateFlow<FindUiState>(FindUiState.Idle)
    val state: StateFlow<FindUiState> = _state.asStateFlow()

    fun onResolutionScreenOpened() = viewModelScope.launch { findTag.warmUp() }

    /** Scanned or typed EPC → validate → navigate to the single target. */
    fun onFind(input: FindInput) = viewModelScope.launch {
        _state.value = FindUiState.Resolving
        when (val r = resolveTarget.resolve(input)) {
            is ResolveResult.Resolved -> startNavigation(r.target)
            is ResolveResult.NotFound -> _state.value = FindUiState.NotFound(r.epc)
            is ResolveResult.Invalid  -> _state.value = FindUiState.Invalid(r.reason)
            is ResolveResult.Failure  -> _state.value = FindUiState.Error(r.reason)
        }
    }

    /** Search path: show candidate EPCs, associate picks one. */
    fun onSearch(query: String) = viewModelScope.launch {
        _state.value = FindUiState.Resolving
        val candidates = resolveTarget.search(query)
        _state.value = if (candidates.isEmpty()) FindUiState.NotFound(query)
                       else FindUiState.PickEpc(candidates)
    }

    fun onEpcChosen(target: EpcTarget) = startNavigation(target)

    private var navigationJob: Job? = null

    /** Reached ONLY with a resolved EPC — the structural gate. */
    private fun startNavigation(target: EpcTarget) {
        navigationJob?.cancel()
        navigationJob = viewModelScope.launch {
            findTag.find(target.epc)          // ← interpretation pipeline triggers here
                .catch { _state.value = FindUiState.Error(it.message ?: "navigation failed") }
                .collect { _state.value = FindUiState.Navigating(it) }
        }
    }
}
