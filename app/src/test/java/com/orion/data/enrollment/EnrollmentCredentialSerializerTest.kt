package com.orion.data.enrollment

import androidx.datastore.core.CorruptionException
import com.orion.core.enrollment.EnrollmentCredential
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private fun credential(expiresAt: Long? = 2_000L) = EnrollmentCredential(
    organizationId = "org-1",
    siteId = "site-1",
    deviceId = "device-1",
    token = "opaque-token-value",
    issuedAt = 1_000L,
    expiresAt = expiresAt
)

/**
 * Covers [EnrollmentCredentialSerializer]'s manual byte encoding/decoding in
 * isolation -- no Android/DataStore-file/Tink dependency is exercised here,
 * only the plain InputStream/OutputStream round-trip.
 */
class EnrollmentCredentialSerializerTest {

    @Test
    fun `round-trips a fully populated credential`() = runTest {
        val original = credential(expiresAt = 2_000L)

        val bytes = ByteArrayOutputStream().also {
            EnrollmentCredentialSerializer.writeTo(original, it)
        }.toByteArray()
        val decoded = EnrollmentCredentialSerializer.readFrom(ByteArrayInputStream(bytes))

        assertEquals(original, decoded)
    }

    @Test
    fun `round-trips a credential with no expiry`() = runTest {
        val original = credential(expiresAt = null)

        val bytes = ByteArrayOutputStream().also {
            EnrollmentCredentialSerializer.writeTo(original, it)
        }.toByteArray()
        val decoded = EnrollmentCredentialSerializer.readFrom(ByteArrayInputStream(bytes))

        assertEquals(original, decoded)
    }

    @Test
    fun `round-trips null as not-enrolled`() = runTest {
        val bytes = ByteArrayOutputStream().also {
            EnrollmentCredentialSerializer.writeTo(null, it)
        }.toByteArray()
        val decoded = EnrollmentCredentialSerializer.readFrom(ByteArrayInputStream(bytes))

        assertNull(decoded)
    }

    @Test
    fun `defaultValue is null (not-enrolled)`() {
        assertNull(EnrollmentCredentialSerializer.defaultValue)
    }

    @Test
    fun `round-trips a token longer than 65535 bytes (writeUTF's cap)`() = runTest {
        // The whole reason length-prefixed encoding was used instead of
        // DataOutputStream.writeUTF/readUTF: token is opaque and has no assumed
        // length. This guards against silently reintroducing that 65535-byte cap.
        val original = credential().copy(token = "t".repeat(100_000))

        val bytes = ByteArrayOutputStream().also {
            EnrollmentCredentialSerializer.writeTo(original, it)
        }.toByteArray()
        val decoded = EnrollmentCredentialSerializer.readFrom(ByteArrayInputStream(bytes))

        assertEquals(original, decoded)
    }

    @Test
    fun `truncated bytes surface as CorruptionException`() = runTest {
        val fullBytes = ByteArrayOutputStream().also {
            EnrollmentCredentialSerializer.writeTo(credential(), it)
        }.toByteArray()
        val truncated = fullBytes.copyOf(fullBytes.size / 2)

        try {
            EnrollmentCredentialSerializer.readFrom(ByteArrayInputStream(truncated))
            fail("Expected CorruptionException for truncated bytes")
        } catch (e: CorruptionException) {
            assertTrue(true)
        }
    }

    @Test
    fun `negative length prefix surfaces as CorruptionException`() = runTest {
        // hasCredential = true, followed by a negative length prefix for organizationId.
        val bytes = ByteArrayOutputStream().also { baos ->
            val out = DataOutputStream(baos)
            out.writeBoolean(true)
            out.writeInt(-1)
            out.flush()
        }.toByteArray()

        try {
            EnrollmentCredentialSerializer.readFrom(ByteArrayInputStream(bytes))
            fail("Expected CorruptionException for negative length prefix")
        } catch (e: CorruptionException) {
            assertTrue(true)
        }
    }

    @Test
    fun `oversized length prefix surfaces as CorruptionException, not OutOfMemoryError`() = runTest {
        // hasCredential = true, followed by a length prefix that is well within Int range
        // but far larger than any realistic field -- simulating a torn/partially-written
        // file rather than an outright negative value. Must not attempt to allocate a
        // ByteArray of this size.
        val bytes = ByteArrayOutputStream().also { baos ->
            val out = DataOutputStream(baos)
            out.writeBoolean(true)
            out.writeInt(Int.MAX_VALUE - 1)
            out.flush()
        }.toByteArray()

        try {
            EnrollmentCredentialSerializer.readFrom(ByteArrayInputStream(bytes))
            fail("Expected CorruptionException for oversized length prefix")
        } catch (e: CorruptionException) {
            assertTrue(true)
        }
    }
}
