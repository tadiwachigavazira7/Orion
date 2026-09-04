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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart

class FindTagUseCase(
    private val reader: RfidReader,
    private val engine: NavigationEngine = NavigationEngine()
) {
    /** Default path: connect + read + interpret, keyed on a resolved EPC. */
    fun find(targetEpc: String): Flow<NavigationState> = interpret(targetEpc)
        .onStart {
            reader.connect().getOrThrow()
            reader.startInventory().getOrThrow()
        }
        .conflate()
        .onCompletion {
            // Cleanup runs on every terminal path (success, cancel, failure).
            // Guarded so a teardown error can't mask the original cause, and
            // so partial-init states (never connected, or connected but never
            // inventorying) are safe.
            runCatching { reader.stopInventory() }
            runCatching { reader.disconnect() }
        }

    /** Optional latency optimization: warm the reader on the resolution screen. */
    suspend fun warmUp(): Result<Unit> =
        reader.connect().mapCatching { reader.startInventory().getOrThrow() }

    /** Sets the engine target, then filters/maps the reader's live observation stream. */
    fun interpret(targetEpc: String): Flow<NavigationState> = reader.observations
        .onStart { engine.setTarget(targetEpc) }
        .filter { it.epc == targetEpc }
        .map { engine.onObservation(it) }
        .conflate()
}