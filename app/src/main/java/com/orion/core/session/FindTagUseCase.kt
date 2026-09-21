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
            // NonCancellable: on cancellation (e.g. user backs out) suspending teardown
            // calls would otherwise throw immediately and leak the reader.
            withContext(NonCancellable) {
                // Teardown failures are intentionally not propagated: onCompletion also runs
                // for a failing/cancelled flow, and a teardown error must not replace that
                // original cause. They are reported to stderr rather than dropped silently
                // (no logging framework in core; core stays free of android.util.Log).
                runCatching { reader.stopInventory() }.onFailure { System.err.println("Orion: stopInventory failed: $it") }
                runCatching { reader.disconnect() }.onFailure { System.err.println("Orion: disconnect failed: $it") }
            }
        }

    /** Optional latency optimization: warm the reader on the resolution screen. */
    suspend fun warmUp(): Result<Unit> =
        reader.connect().mapCatching { reader.startInventory().getOrThrow() }

    /** Sets the engine target, then filters/maps the reader's live observation stream. */
    fun interpret(targetEpc: String): Flow<NavigationState> = reader.observations
        .onStart {
            engine.setTarget(targetEpc)
            // setTarget only resets on a *different* EPC; a repeat search for the same EPC
            // must not inherit the previous session's smoothed RSSI.
            engine.reset()
        }
        .filter { it.epc == targetEpc }
        .map { engine.onObservation(it) }
        .conflate()
}