// ============================================================
// core/rfid/RfidObservation.kt   — vendor-neutral domain model
// EPC is the tag identity everywhere (CLAUDE.md §6a). No vendor imports here.
// ============================================================
package com.orion.core.rfid

/**
 * @param rssi signal strength in dBm, higher (less negative) = stronger. This is the unit
 *   the navigation engine REQUIRES: finite and within [NavigationEngine.MIN_VALID_DBM,
 *   NavigationEngine.MAX_VALID_DBM]; out-of-range reads are rejected, not interpreted.
 *   Adapters must convert vendor units to dBm (or drop the read).
 * @param rawRssi vendor-reported value before conversion, kept for diagnostics only;
 *   never used by the engine and never mixed with [rssi].
 * @param timestamp device-clock epoch millis (see CLAUDE.md §6 — timestamp source
 *   note: temporal filtering depends on a single, documented time base).
 */
data class RfidObservation(
    val epc: String,
    val readerId: String,
    val rssi: Double,
    val rawRssi: Int? = null,
    val timestamp: Long
)
