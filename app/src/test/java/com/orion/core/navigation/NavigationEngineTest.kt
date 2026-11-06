package com.orion.core.navigation

import com.orion.core.rfid.RfidObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TARGET_EPC = "30245BFB8386AA80000186A1"

private fun obs(epc: String, rssi: Double) =
    RfidObservation(epc = epc, readerId = "test-reader", rssi = rssi, timestamp = 0L)

class NavigationEngineTest {

    @Test
    fun `no reads yet reports signal gap, not a fake default`() {
        val state = NavigationEngine().onSignalGap()

        assertNull(state.proximity)
        assertEquals(0.0, state.confidence, 0.0)
        assertEquals(Trend.UNKNOWN, state.trend)
        assertFalse(state.targetAcquired)
    }

    @Test
    fun `observation for a different tag is ignored and reports signal gap`() {
        val engine = NavigationEngine()
        engine.setTarget(TARGET_EPC)

        val state = engine.onObservation(obs("SOME-OTHER-EPC", rssi = -30.0))

        assertNull(state.proximity)
        assertEquals(Trend.UNKNOWN, state.trend)
        assertFalse(state.targetAcquired)
    }

    @Test
    fun `first observation for the target has no trend yet`() {
        val engine = NavigationEngine()
        engine.setTarget(TARGET_EPC)

        val state = engine.onObservation(obs(TARGET_EPC, rssi = -70.0))

        assertEquals(Trend.UNKNOWN, state.trend)
        // One sample in an 8-sample window: low but real confidence, never a flat constant.
        assertTrue("confidence=${state.confidence}", state.confidence > 0.0 && state.confidence < 0.5)
        assertFalse(state.targetAcquired)
    }

    @Test
    fun `rising rssi is reported as hotter`() {
        val engine = NavigationEngine()
        engine.setTarget(TARGET_EPC)
        engine.onObservation(obs(TARGET_EPC, rssi = -70.0))

        val state = engine.onObservation(obs(TARGET_EPC, rssi = -40.0))

        assertEquals(Trend.HOTTER, state.trend)
    }

    @Test
    fun `falling rssi is reported as colder`() {
        val engine = NavigationEngine()
        engine.setTarget(TARGET_EPC)
        engine.onObservation(obs(TARGET_EPC, rssi = -40.0))

        val state = engine.onObservation(obs(TARGET_EPC, rssi = -70.0))

        assertEquals(Trend.COLDER, state.trend)
    }

    @Test
    fun `unchanged rssi is reported as steady`() {
        val engine = NavigationEngine()
        engine.setTarget(TARGET_EPC)
        engine.onObservation(obs(TARGET_EPC, rssi = -60.0))

        val state = engine.onObservation(obs(TARGET_EPC, rssi = -60.0))

        assertEquals(Trend.STEADY, state.trend)
    }

    @Test
    fun `a single strong reading does not acquire the target`() {
        val engine = NavigationEngine(acquiredRssiThreshold = -35.0)
        engine.setTarget(TARGET_EPC)

        assertFalse(engine.onObservation(obs(TARGET_EPC, rssi = -30.0)).targetAcquired)
    }

    @Test
    fun `sustained strong readings acquire the target`() {
        val engine = NavigationEngine(acquiredRssiThreshold = -35.0, acquireConsecutiveReads = 3)
        engine.setTarget(TARGET_EPC)

        engine.onObservation(obs(TARGET_EPC, rssi = -30.0))
        engine.onObservation(obs(TARGET_EPC, rssi = -30.0))
        assertTrue(engine.onObservation(obs(TARGET_EPC, rssi = -30.0)).targetAcquired)
    }

    @Test
    fun `a weak reading breaks the acquisition streak`() {
        val engine = NavigationEngine(acquiredRssiThreshold = -35.0, acquireConsecutiveReads = 3)
        engine.setTarget(TARGET_EPC)
        engine.onObservation(obs(TARGET_EPC, rssi = -30.0))
        engine.onObservation(obs(TARGET_EPC, rssi = -30.0))
        engine.onObservation(obs(TARGET_EPC, rssi = -70.0))

        assertFalse(engine.onObservation(obs(TARGET_EPC, rssi = -30.0)).targetAcquired)
    }

    @Test
    fun `confidence grows with samples and drops when readings disagree`() {
        val steady = NavigationEngine().also { it.setTarget(TARGET_EPC) }
        var steadyConf = 0.0
        repeat(8) { steadyConf = steady.onObservation(obs(TARGET_EPC, rssi = -60.0)).confidence }
        val firstConf = NavigationEngine().also { it.setTarget(TARGET_EPC) }
            .onObservation(obs(TARGET_EPC, rssi = -60.0)).confidence

        val noisy = NavigationEngine().also { it.setTarget(TARGET_EPC) }
        var noisyConf = 0.0
        repeat(8) { i -> noisyConf = noisy.onObservation(obs(TARGET_EPC, rssi = if (i % 2 == 0) -40.0 else -80.0)).confidence }

        assertTrue(steadyConf > firstConf)
        assertTrue("steady=$steadyConf noisy=$noisyConf", steadyConf > noisyConf)
    }

    @Test
    fun `out-of-range or non-finite rssi is rejected, not interpreted`() {
        val engine = NavigationEngine()
        engine.setTarget(TARGET_EPC)

        listOf(5.0, -300.0, Double.NaN, Double.POSITIVE_INFINITY).forEach {
            assertFalse(NavigationEngine.isValidRssi(it))
            assertThrows(IllegalArgumentException::class.java) { engine.onObservation(obs(TARGET_EPC, rssi = it)) }
        }
    }

    @Test
    fun `restarting a search for the same epc starts clean`() {
        val engine = NavigationEngine()
        engine.setTarget(TARGET_EPC)
        engine.onObservation(obs(TARGET_EPC, rssi = -70.0))
        engine.onObservation(obs(TARGET_EPC, rssi = -40.0))

        engine.setTarget(TARGET_EPC)
        val first = engine.onObservation(obs(TARGET_EPC, rssi = -60.0))

        assertEquals(Trend.UNKNOWN, first.trend)
        assertEquals(-60.0, -80.0 + first.proximity!! * 45.0, 1e-9)  // no smoothing carried over
    }

    @Test
    fun `weak reading does not acquire the target`() {
        val engine = NavigationEngine(acquiredRssiThreshold = -35.0)
        engine.setTarget(TARGET_EPC)

        val state = engine.onObservation(obs(TARGET_EPC, rssi = -70.0))

        assertFalse(state.targetAcquired)
    }
}
