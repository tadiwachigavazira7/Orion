package com.orion.core.navigation

import com.orion.core.rfid.RfidObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertEquals(0.6, state.confidence, 0.0)
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
    fun `strong first reading acquires the target immediately`() {
        val engine = NavigationEngine(acquiredRssiThreshold = -35.0)
        engine.setTarget(TARGET_EPC)

        val state = engine.onObservation(obs(TARGET_EPC, rssi = -30.0))

        assertTrue(state.targetAcquired)
    }

    @Test
    fun `weak reading does not acquire the target`() {
        val engine = NavigationEngine(acquiredRssiThreshold = -35.0)
        engine.setTarget(TARGET_EPC)

        val state = engine.onObservation(obs(TARGET_EPC, rssi = -70.0))

        assertFalse(state.targetAcquired)
    }
}
