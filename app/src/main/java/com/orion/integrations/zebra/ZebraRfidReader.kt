// ============================================================
// integrations/zebra/ZebraRfidReader.kt
//
// TODO: NOT IMPLEMENTED. This is a signature-only stub.
//
// Real Zebra RFID SDK details (dependency coordinates, reader connection
// API, event/listener model, error codes, antenna/session configuration)
// are not yet known and must NOT be guessed at (CLAUDE.md §15, §27).
//
// When Zebra SDK integration details are available:
//   - Add the Zebra SDK dependency to app/build.gradle.kts (or a dedicated
//     module) — scoped to this package only.
//   - Implement connect/startInventory/stopInventory/disconnect against
//     the real SDK lifecycle.
//   - Convert every Zebra SDK tag-read callback into an RfidObservation
//     (CLAUDE.md §6) — no vendor types may leak out of this file.
//   - This class must contain ONLY SDK plumbing: no localization,
//     navigation, or UI logic (CLAUDE.md §7).
// ============================================================
package com.orion.integrations.zebra

import com.orion.core.rfid.RfidObservation
import com.orion.core.rfid.RfidReader
import kotlinx.coroutines.flow.Flow

class ZebraRfidReader : RfidReader {
    override val observations: Flow<RfidObservation>
        get() = throw NotImplementedError(
            "Zebra RFID SDK integration is not implemented — real SDK details are required."
        )

    override suspend fun connect(): Result<Unit> =
        throw NotImplementedError(
            "Zebra RFID SDK integration is not implemented — real SDK details are required."
        )

    override suspend fun startInventory(): Result<Unit> =
        throw NotImplementedError(
            "Zebra RFID SDK integration is not implemented — real SDK details are required."
        )

    override suspend fun stopInventory(): Result<Unit> =
        throw NotImplementedError(
            "Zebra RFID SDK integration is not implemented — real SDK details are required."
        )

    override suspend fun disconnect(): Unit =
        throw NotImplementedError(
            "Zebra RFID SDK integration is not implemented — real SDK details are required."
        )
}
