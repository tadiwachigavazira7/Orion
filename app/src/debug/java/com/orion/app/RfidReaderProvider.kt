package com.orion.app

import com.orion.core.rfid.RfidReader
import com.orion.integrations.mock.MockRfidConfig
import com.orion.integrations.mock.MockRfidReader

/**
 * DEBUG variant of the RFID reader seam (the release variant lives in src/release with the
 * same signature). Selected by source set, so no BuildConfig.DEBUG checks anywhere.
 *
 * Uses a well-formed SGTIN-96 EPC known to FakeEpcLookup, because ResolveTargetUseCase
 * rejects non-SGTIN EPCs (e.g. E28068...) as Invalid before navigation can start.
 * endRssi is -30 (not -45) because NavigationEngine only acquires at smoothed RSSI >= -35.
 * A different, strong decoy EPC is interleaved to exercise filter-to-target.
 */
internal const val MOCK_TARGET_EPC = "30245BFB8386AA80000186A1"
internal const val MOCK_DECOY_EPC = "30245BFB8386AAC0000186A2"

internal fun provideRfidReader(): RfidReader = MockRfidReader(
    MockRfidConfig(
        targetEpc = MOCK_TARGET_EPC,
        startRssi = -75,
        endRssi = -30,
        step = 3,
        intervalMs = 500,
        decoyEpc = MOCK_DECOY_EPC
    )
)
