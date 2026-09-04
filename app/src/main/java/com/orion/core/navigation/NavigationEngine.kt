// ============================================================
// core/navigation/NavigationEngine.kt
// Pure logic. No Android, no vendor SDK. Fully unit-testable.
// ============================================================
package com.orion.core.navigation

import com.orion.core.rfid.RfidObservation

data class NavigationState(
    val proximity: Double?,     // 0.0 far … 1.0 at target; null if unknown
    val confidence: Double,
    val trend: Trend,
    val targetAcquired: Boolean
) 

enum class Trend { HOTTER, COLDER, STEADY, UNKNOWN }

class NavigationEngine(
    private val smoothing: Double = 0.3,
    private val acquiredRssiThreshold: Double = -35.0
) {
    private var targetEpc: String? = null
    private var smoothedRssi: Double? = null
    private var lastSmoothed: Double? = null

    /** Call when the associate selects or changes the target. */
    fun setTarget(epc: String) {
        if (epc != targetEpc) {
            targetEpc = epc
            reset()
        }
    }

    /** Clear tracking state (new search, or target lost). */
    fun reset() {
        smoothedRssi = null
        lastSmoothed = null
    }

    fun onObservation(obs: RfidObservation): NavigationState {
        val target = targetEpc ?: return onSignalGap()
        if (obs.epc != target) return currentState()

        val prev = smoothedRssi
        smoothedRssi = if (prev == null) obs.rssi else prev + smoothing * (obs.rssi - prev)
        lastSmoothed = prev
        return currentState()
    }

    fun onSignalGap(): NavigationState =
        NavigationState(proximity = null, confidence = 0.0, trend = Trend.UNKNOWN, targetAcquired = false)

    private fun currentState(): NavigationState {
        val s = smoothedRssi ?: return onSignalGap()
        val trend = when {
            lastSmoothed == null -> Trend.UNKNOWN
            s - lastSmoothed!! > 0.5 -> Trend.HOTTER
            s - lastSmoothed!! < -0.5 -> Trend.COLDER
            else -> Trend.STEADY
        }
        // PLACEHOLDER proximity mapping — MUST be empirically validated on real hardware.
        val proximity = ((s + 80.0) / 45.0).coerceIn(0.0, 1.0)
        return NavigationState(
            proximity = proximity,
            confidence = 0.6,  // PLACEHOLDER — flat until confidence can be modeled from real data
            trend = trend,
            targetAcquired = s >= acquiredRssiThreshold
        )
    }
}