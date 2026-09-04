// ============================================================
// core/rfid/RfidObservation.kt   — vendor-neutral domain model
// EPC is the tag identity everywhere (CLAUDE.md §6a). No vendor imports here.
// ============================================================
package com.orion.core.rfid

/**
 * @param rssi normalized RSSI (see CLAUDE.md §6 — units note: keep raw and
 *   normalized values distinct so they are never mixed silently).
 * @param rawRssi raw reader value (typically dBm) before normalization, if available.
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
