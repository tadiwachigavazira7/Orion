package com.orion.app

import org.junit.Assert.assertEquals
import org.junit.Test

private const val EPSILON = 0.0001

class AngleMathTest {

    @Test
    fun `normalizeDegrees wraps zero and full rotations to zero`() {
        assertEquals(0.0, normalizeDegrees(0.0), EPSILON)
        assertEquals(0.0, normalizeDegrees(360.0), EPSILON)
        assertEquals(0.0, normalizeDegrees(720.0), EPSILON)
    }

    @Test
    fun `normalizeDegrees wraps negative angles into range`() {
        assertEquals(330.0, normalizeDegrees(-30.0), EPSILON)
        assertEquals(330.0, normalizeDegrees(-390.0), EPSILON)
    }

    @Test
    fun `normalizeDegrees leaves in-range angles unchanged`() {
        assertEquals(90.0, normalizeDegrees(90.0), EPSILON)
        assertEquals(180.0, normalizeDegrees(180.0), EPSILON)
        assertEquals(270.0, normalizeDegrees(270.0), EPSILON)
    }

    @Test
    fun `normalizeDegrees does not wrap a value just under 360`() {
        assertEquals(359.999, normalizeDegrees(359.999), EPSILON)
    }

    @Test
    fun `shortestAngleDelta takes the short way across the 0-360 seam`() {
        assertEquals(20.0, shortestAngleDelta(350.0, 10.0), EPSILON)
        assertEquals(-20.0, shortestAngleDelta(10.0, 350.0), EPSILON)
    }

    @Test
    fun `shortestAngleDelta handles a half turn and no turn`() {
        assertEquals(180.0, shortestAngleDelta(0.0, 180.0), EPSILON)
        assertEquals(0.0, shortestAngleDelta(0.0, 0.0), EPSILON)
    }

    @Test
    fun `shortestAngleDelta at the exact -180 boundary reports +180, never -180`() {
        // The declared range is (-180.0, 180.0] — -180.0 itself must never be returned.
        assertEquals(180.0, shortestAngleDelta(180.0, 0.0), EPSILON)
    }
}
