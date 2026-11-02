package com.orion.data.enrollment

import androidx.datastore.core.CorruptionException
import com.orion.core.enrollment.DenialReason
import com.orion.core.enrollment.EnrollmentCredential
import com.orion.core.enrollment.OfflineAuthorization
import com.orion.core.enrollment.StoredEnrollment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

private val CREDENTIAL = EnrollmentCredential("ORG-1", "SITE-1", "device-1", "opaque-token-value")
private val AUTHORIZATION = OfflineAuthorization(
    serverTimeEpochMs = 1_800_000_000_000L,
    offlineValidUntilEpochMs = 1_800_086_400_000L,
    receivedAtElapsedRealtimeMs = 123_456L,
    bootCount = 42
)

private suspend fun write(value: PersistedEnrollment): ByteArray =
    ByteArrayOutputStream().also { EnrollmentRecordSerializer.writeTo(value, it) }.toByteArray()

private suspend fun read(bytes: ByteArray): PersistedEnrollment =
    EnrollmentRecordSerializer.readFrom(ByteArrayInputStream(bytes))

private suspend fun roundTrip(record: StoredEnrollment): StoredEnrollment? =
    (read(write(PersistedEnrollment.Present(record))) as PersistedEnrollment.Present).record

/** Bytes exactly as the pre-v2 EnrollmentCredentialSerializer wrote them. */
private fun legacyBytes(token: String = "legacy-token", expiresAt: Long? = null): ByteArray =
    ByteArrayOutputStream().also { baos ->
        val out = DataOutputStream(baos)
        out.writeBoolean(true)
        for (s in listOf("org-legacy", "site-legacy", "device-legacy", token)) {
            val b = s.toByteArray(Charsets.UTF_8)
            out.writeInt(b.size)
            out.write(b)
        }
        out.writeLong(1_000L) // issuedAt
        if (expiresAt != null) {
            out.writeBoolean(true)
            out.writeLong(expiresAt)
        } else {
            out.writeBoolean(false)
        }
        out.flush()
    }.toByteArray()

private suspend fun assertCorrupt(bytes: ByteArray) {
    try {
        EnrollmentRecordSerializer.readFrom(ByteArrayInputStream(bytes))
        fail("Expected CorruptionException")
    } catch (e: CorruptionException) {
        // expected: ReplaceFileCorruptionHandler resets to "nothing stored"
    }
}

/**
 * Byte encoding/decoding of the encrypted store's payload in isolation (no Android /
 * DataStore file / Tink), including migration of the legacy format.
 */
class EnrollmentRecordSerializerTest {

    @Test
    fun `round-trips a record with an offline authorization`() = runTest {
        val record = StoredEnrollment(CREDENTIAL, AUTHORIZATION, denial = null)
        assertEquals(record, roundTrip(record))
    }

    @Test
    fun `round-trips an authorization whose boot count was unreadable`() = runTest {
        val record = StoredEnrollment(CREDENTIAL, AUTHORIZATION.copy(bootCount = null), denial = null)
        assertEquals(record, roundTrip(record))
    }

    @Test
    fun `round-trips a credential without authorization`() = runTest {
        val record = StoredEnrollment(CREDENTIAL, offlineAuthorization = null, denial = null)
        assertEquals(record, roundTrip(record))
    }

    @Test
    fun `round-trips every denial reason`() = runTest {
        for (reason in DenialReason.entries) {
            val record = StoredEnrollment(CREDENTIAL, offlineAuthorization = null, denial = reason)
            assertEquals(record, roundTrip(record))
        }
    }

    @Test
    fun `a denial never persists alongside an offline authorization`() = runTest {
        val inconsistent = StoredEnrollment(CREDENTIAL, AUTHORIZATION, DenialReason.REVOKED)
        assertEquals(StoredEnrollment(CREDENTIAL, null, DenialReason.REVOKED), roundTrip(inconsistent))
    }

    @Test
    fun `round-trips empty as not-enrolled and writes the v2 marker`() = runTest {
        val bytes = write(PersistedEnrollment.Empty)
        assertEquals(FORMAT_VERSION_2, bytes[0].toInt())
        assertEquals(PersistedEnrollment.Empty, read(bytes))
        assertEquals(PersistedEnrollment.Empty, EnrollmentRecordSerializer.defaultValue)
    }

    @Test
    fun `unreadable marker round-trips and is distinct from empty`() = runTest {
        assertEquals(PersistedEnrollment.Unreadable, read(write(PersistedEnrollment.Unreadable)))
    }

    @Test
    fun `round-trips a token longer than 65535 bytes (writeUTF's cap)`() = runTest {
        val record = StoredEnrollment(CREDENTIAL.copy(token = "t".repeat(100_000)), AUTHORIZATION, null)
        assertEquals(record, roundTrip(record))
    }

    @Test
    fun `legacy credential migrates to a record without authorization`() = runTest {
        for (expiresAt in listOf(null, 9_999L)) {
            val migrated = read(legacyBytes(expiresAt = expiresAt))
            assertEquals(
                PersistedEnrollment.Present(StoredEnrollment(
                    EnrollmentCredential("org-legacy", "site-legacy", "device-legacy", "legacy-token"),
                    offlineAuthorization = null,
                    denial = null
                )),
                migrated
            )
        }
    }

    @Test
    fun `legacy not-enrolled byte reads as nothing stored`() = runTest {
        assertEquals(PersistedEnrollment.Empty, read(byteArrayOf(0)))
    }

    @Test
    fun `migrated legacy record is rewritten in the v2 format`() = runTest {
        val migrated = read(legacyBytes())
        val rewritten = write(migrated)
        assertEquals(FORMAT_VERSION_2, rewritten[0].toInt())
        assertEquals(migrated, read(rewritten))
    }

    @Test
    fun `unknown format byte, bad flag, and bad denial code are corruption`() = runTest {
        assertCorrupt(byteArrayOf(9))
        assertCorrupt(byteArrayOf(FORMAT_VERSION_2.toByte(), 5)) // bad record marker

        val good = write(PersistedEnrollment.Present(StoredEnrollment(CREDENTIAL, null, null)))
        val badDenial = good.copyOf().also { it[it.size - 1] = 77 }
        assertCorrupt(badDenial)
    }

    @Test
    fun `truncated v2 and legacy bytes are corruption`() = runTest {
        val v2 = write(PersistedEnrollment.Present(StoredEnrollment(CREDENTIAL, AUTHORIZATION, null)))
        assertCorrupt(v2.copyOf(v2.size - 3))
        val legacy = legacyBytes()
        assertCorrupt(legacy.copyOf(legacy.size / 2))
    }

    @Test
    fun `negative or oversized length prefix is corruption, not OutOfMemoryError`() = runTest {
        for (length in listOf(-1, Int.MAX_VALUE - 1)) {
            val bytes = ByteArrayOutputStream().also { baos ->
                val out = DataOutputStream(baos)
                out.writeByte(FORMAT_VERSION_2)
                out.writeByte(1)
                out.writeInt(length)
                out.flush()
            }.toByteArray()
            assertCorrupt(bytes)
        }
    }
}
