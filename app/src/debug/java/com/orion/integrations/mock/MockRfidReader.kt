// ============================================================
// integrations/mock/MockRfidReader.kt   -- DEBUG-ONLY (src/debug)
//
// Hardware-free RfidReader for emulator / dev builds. Lives in the debug source set so
// it does not exist in release builds and cannot become the production reader.
//
// It only PRODUCES synthetic RfidObservations. It does no filtering, smoothing,
// localization or navigation -- those stay in the real pipeline (CLAUDE.md §7, §8).
//
// Timestamp source: DEVICE clock (epoch millis), via the injectable [clock] (default
// System.currentTimeMillis), matching the documented RfidObservation time base. Emitted
// timestamps are forced strictly increasing (a frozen/virtual clock cannot repeat one).
//
// RSSI: [RfidObservation.rawRssi] is the reader-style integer dBm; [RfidObservation.rssi]
// is the same dBm value as a Double including jitter (this mock applies no normalization).
// ============================================================
package com.orion.integrations.mock

import com.orion.core.rfid.RfidObservation
import com.orion.core.rfid.RfidReader
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Simulation parameters.
 *
 * The target RSSI ramps from [startRssi] toward [endRssi] by [step] dB every [intervalMs],
 * then HOLDS at [endRssi] (plus jitter) until scanning stops.
 *
 * @param decoyEpc if set, an observation of this (different) EPC is emitted each tick too,
 *   at [endRssi] (deliberately strong) to prove the pipeline filters to the target.
 * @param emitTarget when false only the decoy is emitted (the "target never seen" case).
 * @param noiseDb jitter amplitude: each RSSI gets a uniform offset in [-noiseDb, +noiseDb].
 */
data class MockRfidConfig(
    val targetEpc: String = DEFAULT_TARGET_EPC,
    val startRssi: Int = -75,
    val endRssi: Int = -45,
    val step: Int = 3,
    val intervalMs: Long = 500,
    val noiseDb: Double = 1.0,
    val decoyEpc: String? = null,
    val emitTarget: Boolean = true,
    val readerId: String = "mock-reader"
) {
    init {
        require(step > 0) { "step must be positive" }
        require(intervalMs > 0) { "intervalMs must be positive" }
        require(noiseDb >= 0.0) { "noiseDb must be non-negative" }
        require(startRssi <= endRssi) { "startRssi must not exceed endRssi (ramp is toward stronger signal)" }
        require(decoyEpc == null || decoyEpc != targetEpc) { "decoyEpc must differ from targetEpc" }
    }

    companion object {
        const val DEFAULT_TARGET_EPC = "E28068940000000000000001"
    }
}

class MockRfidReader(
    private val config: MockRfidConfig = MockRfidConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default
) : RfidReader {

    private val connected = MutableStateFlow(false)
    private val scanning = MutableStateFlow(false)

    /**
     * Emits nothing while not scanning. Each time scanning (re)starts and this flow is
     * collected, the ramp restarts from startRssi. Cancelling the collector stops emission.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override val observations: Flow<RfidObservation> =
        scanning.flatMapLatest { if (it) generate() else emptyFlow() }

    private fun generate(): Flow<RfidObservation> = flow {
        var tick = 0L
        var lastTimestamp: Long? = null
        fun nextTimestamp(): Long {
            val floor = lastTimestamp?.plus(1) ?: Long.MIN_VALUE
            return maxOf(clock(), floor).also { lastTimestamp = it }
        }
        while (true) {
            val base = minOf(config.startRssi + config.step * tick, config.endRssi.toLong()).toInt()
            if (config.emitTarget) {
                emit(observation(config.targetEpc, base, nextTimestamp()))
            }
            config.decoyEpc?.let { emit(observation(it, config.endRssi, nextTimestamp())) }
            if (base < config.endRssi) tick++
            delay(config.intervalMs)
        }
    }

    private fun observation(epc: String, dbm: Int, timestamp: Long): RfidObservation {
        val jitter = if (config.noiseDb == 0.0) 0.0 else random.nextDouble(-config.noiseDb, config.noiseDb)
        val rssi = dbm + jitter
        return RfidObservation(
            epc = epc,
            readerId = config.readerId,
            rssi = rssi,
            rawRssi = rssi.roundToInt(),
            timestamp = timestamp
        )
    }

    override suspend fun connect(): Result<Unit> {
        connected.value = true
        return Result.success(Unit)
    }

    override suspend fun startInventory(): Result<Unit> {
        if (!connected.value) return Result.failure(IllegalStateException("MockRfidReader not connected"))
        scanning.value = true
        return Result.success(Unit)
    }

    override suspend fun stopInventory(): Result<Unit> {
        scanning.value = false
        return Result.success(Unit)
    }

    override suspend fun disconnect() {
        scanning.value = false
        connected.value = false
    }
}
