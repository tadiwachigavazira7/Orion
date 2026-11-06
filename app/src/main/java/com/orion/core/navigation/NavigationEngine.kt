// ============================================================
// core/navigation/NavigationEngine.kt
// Pure logic. No Android, no vendor SDK. Fully unit-testable.
// ============================================================
package com.orion.core.navigation

import com.orion.core.rfid.RfidObservation
import kotlin.math.sqrt

/**
 * @param proximity RELATIVE signal strength, 0.0 (weak) .. 1.0 (strong); null if unknown.
 *   NOT a distance and NOT calibrated (CLAUDE.md section 10): it only supports
 *   "hotter / colder" guidance.
 * @param confidence 0.0 .. 1.0, derived from how many recent reads back the estimate and how
 *   much they disagree. 0.0 together with null proximity means "no signal" (a signal gap),
 *   which is a different condition from a low-but-valid confidence.
 */
data class NavigationState(
    val proximity: Double?,
    val confidence: Double,
    val trend: Trend,
    val targetAcquired: Boolean
)

enum class Trend { HOTTER, COLDER, STEADY, UNKNOWN }

/**
 * Consumes [RfidObservation.rssi], which MUST be dBm (higher = stronger) and within
 * [MIN_VALID_DBM]..[MAX_VALID_DBM]; see [isValidRssi]. Out-of-range values are rejected,
 * never interpreted.
 *
 * All numeric constants below are UNCALIBRATED pilot placeholders, to be replaced after
 * measurement on the real PDT/tag combination.
 */
class NavigationEngine(
    private val smoothing: Double = 0.3,
    private val acquiredRssiThreshold: Double = -35.0,
    /** Consecutive reads at/above the threshold needed to declare the target acquired. */
    private val acquireConsecutiveReads: Int = DEFAULT_ACQUIRE_CONSECUTIVE_READS
) {
    private var targetEpc: String? = null
    private var smoothedRssi: Double? = null
    private var lastSmoothed: Double? = null
    private var strongStreak = 0
    private val window = ArrayDeque<Double>()

    /** Call when the associate selects a target OR restarts a search: always starts clean. */
    fun setTarget(epc: String) {
        targetEpc = epc
        reset()
    }

    /** Clear tracking state (new search, or target lost). */
    fun reset() {
        smoothedRssi = null
        lastSmoothed = null
        strongStreak = 0
        window.clear()
    }

    fun onObservation(obs: RfidObservation): NavigationState {
        val target = targetEpc ?: return onSignalGap()
        if (obs.epc != target) return currentState()
        require(isValidRssi(obs.rssi)) { "rssi ${obs.rssi} is not dBm in $MIN_VALID_DBM..$MAX_VALID_DBM" }

        val prev = smoothedRssi
        smoothedRssi = if (prev == null) obs.rssi else prev + smoothing * (obs.rssi - prev)
        lastSmoothed = prev
        window.addLast(obs.rssi)
        if (window.size > WINDOW_SIZE) window.removeFirst()
        strongStreak = if (obs.rssi >= acquiredRssiThreshold) strongStreak + 1 else 0
        return currentState()
    }

    /**
     * No usable signal. Breaks the acquisition streak but keeps smoothing/history, so a
     * brief gap does not make the next read look like a first read.
     */
    fun onSignalGap(): NavigationState {
        strongStreak = 0
        return NavigationState(proximity = null, confidence = 0.0, trend = Trend.UNKNOWN, targetAcquired = false)
    }

    private fun currentState(): NavigationState {
        val s = smoothedRssi ?: return onSignalGap()
        val last = lastSmoothed
        val trend = when {
            last == null -> Trend.UNKNOWN
            s - last > 0.5 -> Trend.HOTTER
            s - last < -0.5 -> Trend.COLDER
            else -> Trend.STEADY
        }
        // UNCALIBRATED relative-strength mapping, not a distance.
        val proximity = ((s + 80.0) / 45.0).coerceIn(0.0, 1.0)
        return NavigationState(
            proximity = proximity,
            confidence = windowConfidence(),
            trend = trend,
            targetAcquired = strongStreak >= acquireConsecutiveReads
        )
    }

    /** Confidence = (how full the window is) x (how little the recent reads disagree). */
    private fun windowConfidence(): Double {
        val n = window.size
        if (n == 0) return 0.0
        val mean = window.average()
        val stdDev = sqrt(window.sumOf { (it - mean) * (it - mean) } / n)
        val stability = 1.0 - (stdDev / MAX_STDDEV_DB).coerceIn(0.0, 1.0)
        return (n.toDouble() / WINDOW_SIZE) * stability
    }

    companion object {
        const val MIN_VALID_DBM = -120.0
        const val MAX_VALID_DBM = 0.0
        const val DEFAULT_ACQUIRE_CONSECUTIVE_READS = 3  // UNCALIBRATED
        private const val WINDOW_SIZE = 8                // UNCALIBRATED
        private const val MAX_STDDEV_DB = 10.0           // UNCALIBRATED: spread at which stability hits 0

        fun isValidRssi(rssi: Double): Boolean = rssi in MIN_VALID_DBM..MAX_VALID_DBM  // false for NaN
    }
}
