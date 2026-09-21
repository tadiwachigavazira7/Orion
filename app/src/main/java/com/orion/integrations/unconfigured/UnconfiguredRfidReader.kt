package com.orion.integrations.unconfigured

import com.orion.core.rfid.RfidObservation
import com.orion.core.rfid.RfidReader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Honest placeholder until a real vendor adapter is wired: connect/start fail explicitly. */
class UnconfiguredRfidReader : RfidReader {
    override val observations: Flow<RfidObservation> = emptyFlow()
    override suspend fun connect(): Result<Unit> =
        Result.failure(IllegalStateException("No RFID reader integration is configured for this build"))
    override suspend fun startInventory(): Result<Unit> = connect()
    override suspend fun stopInventory(): Result<Unit> = Result.success(Unit)
    override suspend fun disconnect() {}
}
