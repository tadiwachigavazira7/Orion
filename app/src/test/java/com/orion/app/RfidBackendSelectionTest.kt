package com.orion.app

import org.junit.Assert.assertEquals
import org.junit.Test

class RfidBackendSelectionTest {

    @Test
    fun `only a debug build with the FAKE backend selects the simulation`() {
        assertEquals(RfidSetup.Simulated, selectRfidSetup("FAKE", isDebugBuild = true))
    }

    @Test
    fun `a release build can never select the fakes, whatever the backend value`() {
        listOf("FAKE", "fake", "NONE", "", "ZEBRA").forEach {
            assertEquals(it, RfidSetup.Unconfigured, selectRfidSetup(it, isDebugBuild = false))
        }
    }

    @Test
    fun `a debug build with NONE or an unknown backend stays unconfigured`() {
        listOf("NONE", "", "ZEBRA").forEach {
            assertEquals(it, RfidSetup.Unconfigured, selectRfidSetup(it, isDebugBuild = true))
        }
    }
}
