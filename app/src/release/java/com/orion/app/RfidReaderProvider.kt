package com.orion.app

import com.orion.core.rfid.RfidReader
import com.orion.integrations.unconfigured.UnconfiguredRfidReader

/**
 * RELEASE variant of the RFID reader seam (debug variant lives in src/debug). No real
 * vendor adapter (Zebra/Impinj) is implemented yet, so release honestly reports "not
 * configured". Swap in the real adapter here once one exists. No mock in this variant.
 */
internal fun provideRfidReader(): RfidReader = UnconfiguredRfidReader()
