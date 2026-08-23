// ============================================================
// core/session/FindTagUseCase.kt
// Interpretation pipeline. Requires an already-resolved EPC.
// ============================================================
package com.orion.core.session

import com.orion.core.navigation.NavigationEngine
import com.orion.core.navigation.NavigationState
import com.orion.core.rfid.RfidReader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion

class FindTagUseCase(
    private val reader: RfidReader,
    private val engine: NavigationEngine = NavigationEngine()
) {
    /** Default path: connect + read + interpret, keyed on a resolved EPC. */
    fun find(targetEpc: String): Flow<NavigationState> = flow {
        reader.connect().getOrThrow()
        reader.startInventory().getOrThrow()
        emitAll(interpret(targetEpc))
    }
        .conflate()
        .onCompletion { reader.stopInventory(); reader.disconnect() }

    /** Optional latency optimization: warm the reader on the resolution screen. */
    suspend fun warmUp(): Result<Unit> =
        reader.connect().mapCatching { reader.startInventory().getOrThrow() }

    fun interpret(targetEpc: String): Flow<NavigationState> =
        reader.observations
            .filter { it.epc == targetEpc }
            .map { engine.onObservation(targetEpc, it) }
            .conflate()
}
