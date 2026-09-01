// ============================================================
// app/CompassUiState.kt
// Pure Kotlin — no Android/Compose imports, unit-testable on the JVM.
// Maps NavigationEngine's vendor-independent output onto the exhaustive
// UI states required by CLAUDE.md §12 (NoSignal is distinct from Guiding
// with blank fields, never collapsed into a shared default rendering).
// ============================================================
package com.orion.app

import com.orion.core.navigation.NavigationState
import com.orion.core.navigation.Trend

sealed interface CompassUiState {
    /** Connected, no reading of the target yet (post-launch, pre-first-observation). */
    data object Searching : CompassUiState

    /** Connected, but the target's signal is currently unavailable. */
    data object NoSignal : CompassUiState

    data class Guiding(val proximity: Double, val confidence: Double, val trend: Trend) : CompassUiState

    data object TargetAcquired : CompassUiState

    /**
     * Populated from [FindFlowViewModel]'s `.catch {}` on the navigation-pipeline flow
     * (e.g. a reader connect/startInventory failure, or an unexpected mid-stream
     * exception) — never from [toCompassUiState] below, which only maps a successfully
     * emitted [NavigationState].
     */
    data class Error(val message: String) : CompassUiState
}

/**
 * Maps a single [NavigationState] emission onto a [CompassUiState].
 *
 * `targetAcquired` takes priority over a null `proximity` — in practice
 * [com.orion.core.navigation.NavigationEngine] never emits that combination
 * (see `onSignalGap()`), but this function's own precedence is defined
 * explicitly rather than left to evaluation order.
 */
fun NavigationState.toCompassUiState(): CompassUiState = when {
    targetAcquired -> CompassUiState.TargetAcquired
    proximity == null -> CompassUiState.NoSignal
    else -> CompassUiState.Guiding(proximity, confidence, trend)
}
