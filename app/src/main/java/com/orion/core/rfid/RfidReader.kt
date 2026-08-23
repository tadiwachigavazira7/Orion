// ============================================================
// core/rfid/RfidReader.kt   — the boundary every vendor implements
// ============================================================
package com.orion.core.rfid

import kotlinx.coroutines.flow.Flow

interface RfidReader {
    val observations: Flow<RfidObservation>
    suspend fun connect(): Result<Unit>
    suspend fun startInventory(): Result<Unit>
    suspend fun stopInventory(): Result<Unit>
    suspend fun disconnect()
}
