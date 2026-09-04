package com.orion.app

import com.orion.core.navigation.NavigationState
import com.orion.core.navigation.Trend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompassUiStateTest {

    @Test
    fun `target acquired maps to TargetAcquired`() {
        val state = NavigationState(proximity = 0.95, confidence = 0.8, trend = Trend.HOTTER, targetAcquired = true)

        assertEquals(CompassUiState.TargetAcquired, state.toCompassUiState())
    }

    @Test
    fun `target acquired takes priority even if proximity is null`() {
        // NavigationEngine.onSignalGap() never actually returns targetAcquired = true with a
        // null proximity, but the mapping function's own precedence is the unit under test here.
        val state = NavigationState(proximity = null, confidence = 0.0, trend = Trend.UNKNOWN, targetAcquired = true)

        assertEquals(CompassUiState.TargetAcquired, state.toCompassUiState())
    }

    @Test
    fun `null proximity without acquisition maps to NoSignal`() {
        val state = NavigationState(proximity = null, confidence = 0.0, trend = Trend.UNKNOWN, targetAcquired = false)

        assertEquals(CompassUiState.NoSignal, state.toCompassUiState())
    }

    @Test
    fun `normal reading maps to Guiding carrying proximity, confidence, and trend`() {
        val state = NavigationState(proximity = 0.42, confidence = 0.6, trend = Trend.COLDER, targetAcquired = false)

        val result = state.toCompassUiState()

        assertTrue(result is CompassUiState.Guiding)
        val guiding = result as CompassUiState.Guiding
        assertEquals(0.42, guiding.proximity, 0.0)
        assertEquals(0.6, guiding.confidence, 0.0)
        assertEquals(Trend.COLDER, guiding.trend)
    }
}
