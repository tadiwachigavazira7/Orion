// ============================================================
// core/session/FindTagUseCase.kt
// Interpretation pipeline. Requires an already-resolved EPC.
// ============================================================
package com.orion.core.session

import com.orion.core.navigation.NavigationEngine
import com.orion.core.navigation.NavigationState
import com.orion.core.rfid.RfidReader
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

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
            teardown()
        }

    /** Optional latency optimization: warm the reader on the resolution screen. */
    suspend fun warmUp(): Result<Unit> =
        reader.connect().mapCatching { reader.startInventory().getOrThrow() }

    /**
     * Stops inventory and disconnects outside of [find] -- e.g. to end a warmed-up
     * session when the device loses authorization. Same guarded teardown as
     * [find]'s onCompletion, so it is safe in any partial-init state.
     */
    suspend fun release() = teardown()

    /**
     * Runs under [NonCancellable]: teardown is usually reached BECAUSE the session
     * was cancelled (e.g. device authorization lost), and a real adapter whose
     * stopInventory/disconnect suspends (withContext(IO), SDK callbacks) would
     * otherwise throw CancellationException at its first suspension point and
     * leave the reader connected and inventorying. Teardown is bounded work.
     */
    private suspend fun teardown() = withContext(NonCancellable) {
        runCatching { reader.stopInventory() }
        runCatching { reader.disconnect() }
        Unit
    }

    /** Sets the engine target, then filters/maps the reader's live observation stream. */
    fun interpret(targetEpc: String): Flow<NavigationState> = reader.observations
        .onStart { engine.setTarget(targetEpc) }
        .filter { it.epc == targetEpc }
        .map { engine.onObservation(it) }
        .conflate()
}