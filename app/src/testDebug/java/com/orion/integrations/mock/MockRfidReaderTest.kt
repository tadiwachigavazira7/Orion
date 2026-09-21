package com.orion.integrations.mock

import com.orion.core.navigation.NavigationState
import com.orion.core.rfid.RfidObservation
import com.orion.core.session.FindTagUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class MockRfidReaderTest {

    private val target = "30245BFB8386AA80000186A1"
    private val decoy = "30245BFB8386AAC0000186A2"

    private fun TestScope.reader(config: MockRfidConfig, frozenClock: Boolean = false) = MockRfidReader(
        config,
        clock = if (frozenClock) ({ 1_000L }) else ({ 1_000L + testScheduler.currentTime }),
        random = Random(42)
    )

    private suspend fun MockRfidReader.startScanning() {
        connect().getOrThrow()
        startInventory().getOrThrow()
    }

    @Test
    fun `default config ramps -75 to -45 by 3 then holds`() = runTest {
        val r = reader(MockRfidConfig(noiseDb = 0.0))
        r.startScanning()
        val obs = r.observations.take(14).toList()
        assertEquals(
            listOf(-75, -72, -69, -66, -63, -60, -57, -54, -51, -48, -45, -45, -45, -45),
            obs.map { it.rawRssi }
        )
        assertTrue(obs.all { it.epc == MockRfidConfig.DEFAULT_TARGET_EPC })
    }

    @Test
    fun `emits standard RfidObservation for configured epc with increasing rssi`() = runTest {
        val r = reader(MockRfidConfig(targetEpc = target, noiseDb = 0.0))
        r.startScanning()
        val obs: List<RfidObservation> = r.observations.take(5).toList()
        assertTrue(obs.all { it.epc == target && it.readerId == "mock-reader" })
        assertEquals(obs.map { it.rssi }.sorted(), obs.map { it.rssi })
        assertTrue(obs.last().rssi > obs.first().rssi)
        assertEquals(obs.map { it.rssi.toInt() }, obs.map { it.rawRssi })
    }

    @Test
    fun `noise is bounded and seed-deterministic`() = runTest {
        val cfg = MockRfidConfig(targetEpc = target, noiseDb = 1.5)
        val a = reader(cfg).also { it.startScanning() }.observations.take(10).toList().map { it.rssi }
        val b = reader(cfg).also { it.startScanning() }.observations.take(10).toList().map { it.rssi }
        assertEquals(a, b)
        val noiseless = MockRfidConfig(targetEpc = target, noiseDb = 0.0)
        val clean = reader(noiseless).also { it.startScanning() }.observations.take(10).toList().map { it.rssi }
        a.zip(clean).forEach { (n, c) -> assertTrue(kotlin.math.abs(n - c) <= 1.5) }
        assertTrue(a != clean)
    }

    @Test
    fun `timestamps strictly increase even with a frozen clock`() = runTest {
        val r = reader(MockRfidConfig(decoyEpc = decoy), frozenClock = true)
        r.startScanning()
        val ts = r.observations.take(10).toList().map { it.timestamp }
        assertTrue(ts.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun `timestamps follow the injected device clock`() = runTest {
        val r = reader(MockRfidConfig(intervalMs = 500, noiseDb = 0.0))
        r.startScanning()
        val ts = r.observations.take(3).toList().map { it.timestamp }
        assertEquals(listOf(1_000L, 1_500L, 2_000L), ts)
    }

    @Test
    fun `no observations until inventory starts and after it stops`() = runTest {
        val r = reader(MockRfidConfig(targetEpc = target))
        val seen = mutableListOf<RfidObservation>()
        val job = launch { r.observations.collect { seen += it } }
        advanceTimeBy(5_000)
        assertTrue(seen.isEmpty())

        r.startScanning()
        advanceTimeBy(1_600)
        assertTrue(seen.isNotEmpty())

        r.stopInventory().getOrThrow()
        advanceTimeBy(1)
        val countAtStop = seen.size
        advanceTimeBy(10_000)
        assertEquals(countAtStop, seen.size)
        job.cancel()
    }

    @Test
    fun `startInventory before connect fails explicitly`() = runTest {
        val r = reader(MockRfidConfig())
        assertTrue(r.startInventory().isFailure)
    }

    @Test
    fun `collector cancellation stops emission`() = runTest {
        val r = reader(MockRfidConfig(noiseDb = 0.0))
        r.startScanning()
        val seen = mutableListOf<RfidObservation>()
        val job: Job = launch { r.observations.collect { seen += it } }
        advanceTimeBy(1_100)
        job.cancel()
        val n = seen.size
        advanceTimeBy(10_000)
        assertEquals(n, seen.size)
    }

    @Test
    fun `decoy observations are interleaved with a different epc`() = runTest {
        val r = reader(MockRfidConfig(targetEpc = target, decoyEpc = decoy))
        r.startScanning()
        val obs = r.observations.take(6).toList()
        assertEquals(listOf(target, decoy, target, decoy, target, decoy), obs.map { it.epc })
    }

    @Test
    fun `decoy-only mode never emits the target epc`() = runTest {
        val r = reader(MockRfidConfig(targetEpc = target, decoyEpc = decoy, emitTarget = false))
        r.startScanning()
        assertTrue(r.observations.take(8).toList().all { it.epc == decoy })
    }

    // ---- pipeline-level: mock -> FindTagUseCase (filter + NavigationEngine) ----

    @Test
    fun `pipeline reaches TargetAcquired when the ramp gets strong enough`() = runTest {
        val r = reader(MockRfidConfig(targetEpc = target, endRssi = -30, decoyEpc = decoy))
        val acquired = FindTagUseCase(r).find(target).first { it.targetAcquired }
        assertTrue(acquired.targetAcquired)
    }

    @Test
    fun `pipeline never acquires the requested target from wrong-epc reads alone`() = runTest {
        val r = reader(MockRfidConfig(targetEpc = target, endRssi = -30, decoyEpc = decoy, emitTarget = false))
        val states = mutableListOf<NavigationState>()
        val job = launch { FindTagUseCase(r).find(target).collect { states += it } }
        advanceTimeBy(120_000)
        job.cancel()
        assertTrue("filter-to-target must drop decoy reads entirely", states.isEmpty())
        assertFalse(states.any { it.targetAcquired })
    }

    @Test
    fun `pipeline with the default -45 end never acquires (engine threshold is -35)`() = runTest {
        val r = reader(MockRfidConfig(targetEpc = target, noiseDb = 0.0))
        val states = mutableListOf<NavigationState>()
        val job = launch { FindTagUseCase(r).find(target).collect { states += it } }
        advanceTimeBy(60_000)
        job.cancel()
        assertTrue(states.isNotEmpty())
        assertNotNull(states.last().proximity)
        assertFalse(states.any { it.targetAcquired })
    }
}
