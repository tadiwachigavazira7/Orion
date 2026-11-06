// ============================================================
// core/rfid/RfidReader.kt   — the boundary every vendor implements
// ============================================================
package com.orion.core.rfid

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Vendor-neutral reader connection health, so reader failure is distinct from target silence. */
sealed interface ReaderStatus {
    data object Connected : ReaderStatus
    data class Disconnected(val reason: String) : ReaderStatus
}

interface RfidReader {
    val observations: Flow<RfidObservation>

    /**
     * Connection health. Default is an empty flow = "this reader cannot report status";
     * real adapters should override so a lost reader surfaces as an error, not as NoSignal.
     */
    val status: Flow<ReaderStatus> get() = emptyFlow()

    suspend fun connect(): Result<Unit>
    suspend fun startInventory(): Result<Unit>
    suspend fun stopInventory(): Result<Unit>
    suspend fun disconnect()
}
