// ============================================================
// integrations/fake/FakeRfidReader.kt   — hardware-free, for dev + tests
// ============================================================
package com.orion.integrations.fake

import com.orion.core.rfid.RfidObservation
import com.orion.core.rfid.RfidReader
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class FakeRfidReader(private val targetEpc: String) : RfidReader {
    override val observations: Flow<RfidObservation> = flow {
        var rssi = -75.0
        while (true) {
            rssi = (rssi + 1.5).coerceAtMost(-30.0)  // simulate walking closer
            emit(RfidObservation(targetEpc, "fake-reader", rssi, timestamp = System.currentTimeMillis()))
            delay(200)
        }
    }
    override suspend fun connect() = Result.success(Unit)
    override suspend fun startInventory() = Result.success(Unit)
    override suspend fun stopInventory() = Result.success(Unit)
    override suspend fun disconnect() {}
}
