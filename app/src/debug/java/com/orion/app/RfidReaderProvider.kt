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
 * A different, strong decoy EPC is interleaved to exercise filter-to-target.
 *
 * [MOCK_SIMULATE_WALKING_AWAY] is a manual, compile-time toggle for developers verifying the
 * emulator by eye: flip it to `true`, rebuild, and the mock ramps the signal from strong to
 * weak (simulating walking away from the target) to visually exercise the "getting colder" /
 * NoSignal path in CompassScreen. Leave it `false` for the default "walking closer" scenario.
 */
internal const val MOCK_TARGET_EPC = "30245BFB8386AA80000186A1"
internal const val MOCK_DECOY_EPC = "30245BFB8386AAC0000186A2"
private const val MOCK_SIMULATE_WALKING_AWAY = false

internal fun provideRfidReader(): RfidReader = MockRfidReader(
    if (MOCK_SIMULATE_WALKING_AWAY) {
        // "Walking away": signal weakens, never reaching acquisition. startRssi is -50 (not
        // -30) because NavigationEngine acquires at smoothed RSSI >= -35 with no smoothing lag
        // on the first observation, so it must start with headroom below the threshold.
        MockRfidConfig(
            targetEpc = MOCK_TARGET_EPC,
            startRssi = -50,
            endRssi = -90,
            step = 3,
            intervalMs = 500,
            decoyEpc = MOCK_DECOY_EPC
        )
    } else {
        // "Walking closer": endRssi is -30 (not -45) because NavigationEngine only acquires
        // at smoothed RSSI >= -35.
        MockRfidConfig(
            targetEpc = MOCK_TARGET_EPC,
            startRssi = -75,
            endRssi = -30,
            step = 3,
            intervalMs = 500,
            decoyEpc = MOCK_DECOY_EPC
        )
    }
)
