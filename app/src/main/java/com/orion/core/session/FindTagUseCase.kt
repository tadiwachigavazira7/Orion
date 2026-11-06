// ============================================================
// core/session/FindTagUseCase.kt
// Interpretation pipeline. Requires an already-resolved EPC.
// ============================================================
package com.orion.core.session

import com.orion.core.navigation.NavigationEngine
import com.orion.core.navigation.NavigationState
import com.orion.core.rfid.ReaderStatus
import com.orion.core.rfid.RfidObservation
import com.orion.core.rfid.RfidReader
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The reader itself failed (disconnected, lost) - distinct from the target merely being silent. */
class ReaderUnavailableException(val reason: String) : Exception("RFID reader unavailable: $reason")

/**
 * Sole owner of the reader's connect/start/stop/disconnect lifecycle ([warmUp], [find], [release]).
 *
 * @param clock monotonic millisecond clock used ONLY for staleness; never the reader's
 *   timestamps, so reader and device clocks are never mixed. Defaults to [System.nanoTime].
 * @param signalTimeoutMs target silence longer than this is reported as a signal gap.
 * @param onTeardownFailure receives every stop/disconnect failure (exactly once each),
 *   because a cancelled collector has nobody to throw to.
 */
class FindTagUseCase(
    private val reader: RfidReader,
    private val engine: NavigationEngine = NavigationEngine(),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val signalTimeoutMs: Long = DEFAULT_SIGNAL_TIMEOUT_MS,
    private val onTeardownFailure: (Throwable) -> Unit = {}
) {
    init { require(signalTimeoutMs > 0) { "signalTimeoutMs must be positive" } }

    private val lock = Mutex()
    private var connected = false
    private var inventoryRunning = false
    private val rejectedReads = AtomicInteger()

    /** Reads for the target dropped because their RSSI was not valid dBm (see [NavigationEngine.isValidRssi]). */
    val rejectedReadCount: Int get() = rejectedReads.get()

    /** Default path: connect + read + interpret, keyed on a resolved EPC. Releases the reader on every exit. */
    fun find(targetEpc: String): Flow<NavigationState> = flow {
        coroutineScope {
            ensureStarted().getOrThrow()
            launch {
                reader.status.collect {
                    if (it is ReaderStatus.Disconnected) throw ReaderUnavailableException(it.reason)
                }
            }
            emitAll(interpret(targetEpc))
        }
    }.onCompletion { cause ->
        val failure = release().exceptionOrNull() ?: return@onCompletion
        // Already reported to onTeardownFailure; additionally surface it, never replacing the cause.
        if (cause == null) throw failure
        if (cause !is CancellationException) cause.addSuppressed(failure)
    }

    /**
     * Optional latency optimization: warm the reader on the resolution screen. Idempotent
     * (find() will not connect/start twice). On failure nothing is left half-open and the
     * reader is NOT considered ready; find() will try again and surface the real error.
     */
    suspend fun warmUp(): Result<Unit> = ensureStarted().onFailure { release() }

    /** Stops inventory and disconnects whatever was started. Idempotent, uncancellable, never throws. */
    suspend fun release(): Result<Unit> = withContext(NonCancellable) {
        lock.withLock {
            val failures = mutableListOf<Throwable>()
            // Exception (not Throwable) is caught deliberately: each failure is recorded and reported below.
            if (inventoryRunning) {
                inventoryRunning = false
                try { reader.stopInventory().onFailure { failures += it } } catch (e: Exception) { failures += e }
            }
            if (connected) {
                connected = false
                try { reader.disconnect() } catch (e: Exception) { failures += e }
            }
            failures.forEach(onTeardownFailure)
            val first = failures.firstOrNull() ?: return@withLock Result.success(Unit)
            failures.drop(1).forEach(first::addSuppressed)
            Result.failure(first)
        }
    }

    private suspend fun ensureStarted(): Result<Unit> = lock.withLock {
        if (!connected) {
            reader.connect().getOrElse { return Result.failure(it) }
            connected = true
        }
        if (!inventoryRunning) {
            reader.startInventory().getOrElse { return Result.failure(it) }
            inventoryRunning = true
        }
        Result.success(Unit)
    }

    private sealed interface Event {
        data class Read(val obs: RfidObservation) : Event
        data object Tick : Event
    }

    /**
     * Resets the engine for [targetEpc] (so a repeat search starts clean), then maps the live
     * stream. A staleness ticker emits the engine's signal-gap state ONCE when the target has
     * produced no valid read for [signalTimeoutMs] (also when it was never seen), and normal
     * guidance resumes on the next valid read. Unrelated EPCs and invalid-RSSI reads never
     * feed the engine or refresh the timer. Events are merged and handled by one collector,
     * so engine access is serial.
     */
    fun interpret(targetEpc: String): Flow<NavigationState> = flow {
        engine.setTarget(targetEpc)
        var lastReadAt = clock()
        var inGap = false
        val reads = reader.observations
            .filter { it.epc == targetEpc }
            .filter { NavigationEngine.isValidRssi(it.rssi).also { ok -> if (!ok) rejectedReads.incrementAndGet() } }
            .map { Event.Read(it) }
        val ticks = flow {
            while (true) {
                delay((signalTimeoutMs / 4).coerceAtLeast(1))
                emit(Event.Tick)
            }
        }
        merge(reads, ticks).collect { event ->
            when (event) {
                is Event.Read -> {
                    lastReadAt = clock()
                    inGap = false
                    emit(engine.onObservation(event.obs))
                }
                Event.Tick -> if (!inGap && clock() - lastReadAt >= signalTimeoutMs) {
                    inGap = true
                    emit(engine.onSignalGap())
                }
            }
        }
    }.conflate()

    companion object {
        const val DEFAULT_SIGNAL_TIMEOUT_MS = 3_000L  // UNCALIBRATED pilot placeholder
    }
}
