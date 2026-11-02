// ============================================================
// data/enrollment/DataStoreEnrollmentStore.kt
// Android-specific EnrollmentStore backed by Jetpack DataStore, encrypted at
// rest via Tink's AeadSerializer wrapper (AES-256-GCM key held in the Android
// Keystore, accessed through AndroidKeysetManager). This replaces the
// deprecated androidx.security.crypto.EncryptedSharedPreferences/EncryptedFile
// APIs with the officially recommended DataStore + Tink integration
// (androidx.datastore:datastore-tink). This package is allowed to import
// android.*/androidx.* per the architecture in CLAUDE.md §6/§20 --
// core.enrollment must not.
// ============================================================
package com.orion.data.enrollment

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.tink.AeadSerializer
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplate
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.orion.core.enrollment.DenialReason
import com.orion.core.enrollment.EnrollmentDataUnreadableException
import com.orion.core.enrollment.EnrollmentCredential
import com.orion.core.enrollment.EnrollmentStore
import com.orion.core.enrollment.OfflineAuthorization
import com.orion.core.enrollment.SecureStorageUnavailableException
import com.orion.core.enrollment.StoredEnrollment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.ProviderException

// The keyset prefs file and the datastore/ directory are excluded from backup and device
// transfer (res/xml/backup_rules.xml, res/xml/data_extraction_rules.xml): they are bound
// to this device's Keystore key. Keep those rules in sync if these names change.
private const val KEYSET_NAME = "orion_enrollment_keyset"
private const val KEYSET_PREF_FILE_NAME = "orion_enrollment_keyset_prefs"
private const val MASTER_KEY_URI = "android-keystore://orion_enrollment_master_key"
private const val DATASTORE_FILE_NAME = "orion_enrollment_secure.pb"

/** Legacy (pre-v2) first byte: DataOutputStream.writeBoolean(false) -- nothing stored. */
private const val LEGACY_NO_CREDENTIAL: Int = 0
/** Legacy (pre-v2) first byte: DataOutputStream.writeBoolean(true) -- legacy credential layout follows. */
private const val LEGACY_HAS_CREDENTIAL: Int = 1
/** Current format marker. Distinct from the legacy boolean bytes (0/1) so both can be told apart. */
internal const val FORMAT_VERSION_2: Int = 2

private const val RECORD_EMPTY: Int = 0
private const val RECORD_PRESENT: Int = 1
private const val RECORD_UNREADABLE: Int = 2

private const val DENIAL_NONE: Int = 0
private const val DENIAL_REVOKED: Int = 1
private const val DENIAL_INVALID_CREDENTIAL: Int = 2
private const val DENIAL_UNKNOWN_DEVICE: Int = 3

/**
 * What is persisted in the encrypted file. [Unreadable] is written by the corruption
 * handler in place of a record that could not be read, so "broken" stays
 * distinguishable from "never enrolled" across restarts (CLAUDE.md §22) until a new
 * enrollment is saved over it.
 */
internal sealed interface PersistedEnrollment {
    data object Empty : PersistedEnrollment
    data class Present(val record: StoredEnrollment) : PersistedEnrollment
    data object Unreadable : PersistedEnrollment
}

/**
 * Serializes [PersistedEnrollment] ([PersistedEnrollment.Empty] -- "not enrolled" --
 * is the [defaultValue]) to/from a plain byte stream. No JSON/protobuf dependency exists
 * in this project, so fields are encoded manually.
 *
 * Format v2 (always written):
 * ```
 * u8  version = 2
 * u8  record (0 empty, 1 present, 2 unreadable marker)
 * -- if present:
 * str organizationCode, str siteCode, str deviceId, str token
 * u8  hasOfflineAuthorization (0/1)
 *   i64 serverTimeEpochMs, i64 offlineValidUntilEpochMs, i64 receivedAtElapsedRealtimeMs
 *   u8  hasBootCount (0/1) [i32 bootCount]
 * u8  denial (0 none, 1 revoked, 2 invalid credential, 3 unknown device)
 * ```
 * `str` = 4-byte length + UTF-8 bytes (not writeUTF, which caps at 65535 bytes;
 * the token is opaque with no assumed length).
 *
 * Legacy format (read-only migration): first byte is a boolean 0/1, then
 * org/site/device/token strings, `issuedAt` i64, and an optional `expiresAt`.
 * A legacy credential migrates to a v2 record WITHOUT an offline authorization
 * (and its local `expiresAt` is discarded), so online verification is required
 * before access. The next write stores v2.
 *
 * `internal` so the byte round-trip / migration can be covered by a plain JVM test.
 */
internal object EnrollmentRecordSerializer : Serializer<PersistedEnrollment> {

    override val defaultValue: PersistedEnrollment = PersistedEnrollment.Empty

    override suspend fun readFrom(input: InputStream): PersistedEnrollment {
        try {
            val dataIn = DataInputStream(input)
            return when (val first = dataIn.readUnsignedByte()) {
                LEGACY_NO_CREDENTIAL -> PersistedEnrollment.Empty
                LEGACY_HAS_CREDENTIAL -> PersistedEnrollment.Present(readLegacyCredential(dataIn))
                FORMAT_VERSION_2 -> readV2(dataIn)
                else -> throw IOException("Unknown enrollment store format byte: $first")
            }
        } catch (e: EOFException) {
            // Malformed/truncated bytes. Surfaced as CorruptionException (which extends
            // IOException) so the ReplaceFileCorruptionHandler wired up in
            // DataStoreEnrollmentStore.buildDataStore() replaces the file with the
            // explicit Unreadable marker (never with "not enrolled").
            throw CorruptionException("Malformed enrollment store bytes", e)
        } catch (e: IOException) {
            throw CorruptionException("Malformed enrollment store bytes", e)
        }
    }

    private fun readLegacyCredential(dataIn: DataInputStream): StoredEnrollment {
        val credential = EnrollmentCredential(
            organizationCode = readLengthPrefixedString(dataIn),
            siteCode = readLengthPrefixedString(dataIn),
            deviceId = readLengthPrefixedString(dataIn),
            token = readLengthPrefixedString(dataIn)
        )
        dataIn.readLong() // legacy issuedAt: local bookkeeping only, discarded
        if (dataIn.readBoolean()) dataIn.readLong() // legacy expiresAt: superseded by server-issued offline window
        return StoredEnrollment(credential, offlineAuthorization = null, denial = null)
    }

    private fun readV2(dataIn: DataInputStream): PersistedEnrollment {
        when (val marker = dataIn.readUnsignedByte()) {
            RECORD_EMPTY -> return PersistedEnrollment.Empty
            RECORD_UNREADABLE -> return PersistedEnrollment.Unreadable
            RECORD_PRESENT -> Unit
            else -> throw IOException("Invalid record marker: $marker")
        }
        val credential = EnrollmentCredential(
            organizationCode = readLengthPrefixedString(dataIn),
            siteCode = readLengthPrefixedString(dataIn),
            deviceId = readLengthPrefixedString(dataIn),
            token = readLengthPrefixedString(dataIn)
        )
        val authorization = if (readFlag(dataIn)) {
            val serverTime = dataIn.readLong()
            val validUntil = dataIn.readLong()
            val receivedAt = dataIn.readLong()
            val bootCount = if (readFlag(dataIn)) dataIn.readInt() else null
            OfflineAuthorization(
                serverTimeEpochMs = serverTime,
                offlineValidUntilEpochMs = validUntil,
                receivedAtElapsedRealtimeMs = receivedAt,
                bootCount = bootCount
            )
        } else {
            null
        }
        val denial = when (val code = dataIn.readUnsignedByte()) {
            DENIAL_NONE -> null
            DENIAL_REVOKED -> DenialReason.REVOKED
            DENIAL_INVALID_CREDENTIAL -> DenialReason.INVALID_CREDENTIAL
            DENIAL_UNKNOWN_DEVICE -> DenialReason.UNKNOWN_DEVICE
            else -> throw IOException("Unknown denial code: $code")
        }
        // A denial always clears the allowance; never let a stored allowance outlive a denial.
        return PersistedEnrollment.Present(
            StoredEnrollment(credential, if (denial != null) null else authorization, denial)
        )
    }

    override suspend fun writeTo(t: PersistedEnrollment, output: OutputStream) {
        val dataOut = DataOutputStream(output)
        dataOut.writeByte(FORMAT_VERSION_2)
        when (t) {
            PersistedEnrollment.Empty -> dataOut.writeByte(RECORD_EMPTY)
            PersistedEnrollment.Unreadable -> dataOut.writeByte(RECORD_UNREADABLE)
            is PersistedEnrollment.Present -> {
                dataOut.writeByte(RECORD_PRESENT)
                writeRecord(dataOut, t.record)
            }
        }
        dataOut.flush()
    }

    private fun writeRecord(dataOut: DataOutputStream, t: StoredEnrollment) {
        writeLengthPrefixedString(dataOut, t.credential.organizationCode)
        writeLengthPrefixedString(dataOut, t.credential.siteCode)
        writeLengthPrefixedString(dataOut, t.credential.deviceId)
        writeLengthPrefixedString(dataOut, t.credential.token)

        val authorization = t.offlineAuthorization.takeIf { t.denial == null }
        if (authorization == null) {
            dataOut.writeByte(0)
        } else {
            dataOut.writeByte(1)
            dataOut.writeLong(authorization.serverTimeEpochMs)
            dataOut.writeLong(authorization.offlineValidUntilEpochMs)
            dataOut.writeLong(authorization.receivedAtElapsedRealtimeMs)
            val bootCount = authorization.bootCount
            if (bootCount == null) {
                dataOut.writeByte(0)
            } else {
                dataOut.writeByte(1)
                dataOut.writeInt(bootCount)
            }
        }
        dataOut.writeByte(
            when (t.denial) {
                null -> DENIAL_NONE
                DenialReason.REVOKED -> DENIAL_REVOKED
                DenialReason.INVALID_CREDENTIAL -> DENIAL_INVALID_CREDENTIAL
                DenialReason.UNKNOWN_DEVICE -> DENIAL_UNKNOWN_DEVICE
            }
        )
    }

    private fun readFlag(input: DataInputStream): Boolean = when (val b = input.readUnsignedByte()) {
        0 -> false
        1 -> true
        else -> throw IOException("Invalid flag byte: $b")
    }

    private fun writeLengthPrefixedString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readLengthPrefixedString(input: DataInputStream): String {
        val length = input.readInt()
        if (length < 0) throw IOException("Negative length-prefixed string length: $length")
        // A torn/partially-written file can produce a large positive length up to
        // Int.MAX_VALUE. Without this cap, `ByteArray(length)` can throw OutOfMemoryError
        // (an Error, not an Exception) before readFully() gets a chance to throw the
        // EOFException that readFrom() converts to CorruptionException.
        if (length > MAX_LENGTH_PREFIXED_STRING_BYTES) {
            throw IOException(
                "Length-prefixed string length $length exceeds max allowed " +
                    "$MAX_LENGTH_PREFIXED_STRING_BYTES bytes; likely corrupted/torn file"
            )
        }
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}

/**
 * Sane upper bound for a single length-prefixed field, well above any realistic
 * organizationId/siteId/deviceId/token value (the largest exercised in tests is a
 * 100,000-byte token), used to reject an oversized length prefix from a corrupted/torn
 * file before allocating a `ByteArray` of that size. See [readLengthPrefixedString].
 */
private const val MAX_LENGTH_PREFIXED_STRING_BYTES = 1 * 1024 * 1024 // 1 MiB

class DataStoreEnrollmentStore(private val context: Context) : EnrollmentStore {

    // Must resolve to a single DataStore instance for the life of the *process*, not just
    // the life of this DataStoreEnrollmentStore instance: DataStoreFactory.create throws
    // IllegalStateException if two live DataStore instances are created for the same
    // backing file in one process. An instance-level `lazy` is NOT sufficient here --
    // DataStoreEnrollmentStore itself gets reconstructed across ordinary Android lifecycle
    // events (e.g. an Activity destroyed without process death via "Don't keep activities",
    // or the system reclaiming a backgrounded Activity, clears the ViewModelStore and
    // MainActivity.onCreate() does `DataStoreEnrollmentStore(applicationContext)` again),
    // and every instance points at the same backing file
    // (applicationContext.filesDir/datastore/orion_enrollment_secure.pb). Because
    // DataStoreFactory.create() below is never given an explicit `scope:`, it defaults to
    // an unmanaged, never-cancelled CoroutineScope -- the underlying storage connection is
    // intentionally never closed and lives for the process's lifetime, matching the usual
    // `Context.dataStore(...)` singleton-delegate idiom (this class can't use that exact
    // top-level delegate because AeadSerializer needs a Context to build its Aead, which
    // must be supplied before the delegate is created). So the DataStore itself is held in
    // the companion object, keyed process-wide rather than per-instance, built at most once.
    // Double-checked locking (@Volatile + synchronized) avoids a race if two
    // DataStoreEnrollmentStore instances are constructed concurrently early in app startup.
    private val dataStore: DataStore<PersistedEnrollment>
        get() = getOrCreateDataStore(context.applicationContext)

    companion object {
        @Volatile
        private var instance: DataStore<PersistedEnrollment>? = null

        private fun getOrCreateDataStore(appContext: Context): DataStore<PersistedEnrollment> {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val built = buildDataStore(appContext)
                instance = built
                return built
            }
        }

        private fun buildDataStore(appContext: Context): DataStore<PersistedEnrollment> {
            // Registers the AES-GCM key manager Tink needs; must happen before building or
            // using the Aead primitive below.
            AeadConfig.register()

            val keysetHandle = AndroidKeysetManager.Builder()
                .withSharedPref(appContext, KEYSET_NAME, KEYSET_PREF_FILE_NAME)
                .withKeyTemplate(KeyTemplate.createFrom(PredefinedAeadParameters.AES256_GCM))
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
                .keysetHandle

            val aead: Aead = keysetHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)

            val aeadSerializer = AeadSerializer(
                aead = aead,
                wrappedSerializer = EnrollmentRecordSerializer,
                // Unique name for this DataStore file -- prevents ciphertext from this file
                // being swapped into a different encrypted DataStore undetected.
                associatedData = DATASTORE_FILE_NAME.encodeToByteArray()
            )

            return DataStoreFactory.create(
                serializer = aeadSerializer,
                // Replace an unreadable file with an explicit marker, so the next launch still
                // reports "enrollment data could not be read" instead of "not enrolled" (a
                // persisted denial must never silently turn into an empty state).
                corruptionHandler = ReplaceFileCorruptionHandler { PersistedEnrollment.Unreadable },
                produceFile = { File(appContext.filesDir, "datastore/$DATASTORE_FILE_NAME") }
            )
        }
    }

    override suspend fun save(record: StoredEnrollment) = withContext(Dispatchers.IO) {
        // Deliberate: a Keystore/Tink/I/O failure here propagates to the caller.
        // DeviceAuthorizationManager turns a failed save after an enrollment approval
        // into an explicit RecoveryRequired state rather than silently losing it.
        // Overwrites an Unreadable marker, which is how a device recovers (re-enrollment).
        try {
            dataStore.updateData { PersistedEnrollment.Present(record) }
        } catch (e: GeneralSecurityException) {
            // Keyset/Keystore could not be opened, or encryption failed.
            throw SecureStorageUnavailableException("Secure storage could not be used to save enrollment data.", e)
        } catch (e: ProviderException) {
            // Android Keystore failures can also surface as this RuntimeException.
            throw SecureStorageUnavailableException("Secure storage could not be used to save enrollment data.", e)
        }
        Unit
    }

    override suspend fun load(): StoredEnrollment? = withContext(Dispatchers.IO) {
        val persisted = try {
            dataStore.data.first()
        } catch (e: IOException) {
            // DataStore file I/O failure (corruption is normally converted to the Unreadable
            // marker by the corruption handler before reaching here).
            throw EnrollmentDataUnreadableException("Enrollment data could not be read from storage.", e)
        } catch (e: GeneralSecurityException) {
            // The lazy Tink/Keystore-open step (buildDataStore()) failed, e.g. keystore
            // corruption/reset: the secure storage itself is unusable, not just this file.
            throw SecureStorageUnavailableException("Secure storage could not be opened.", e)
        } catch (e: ProviderException) {
            throw SecureStorageUnavailableException("Secure storage could not be opened.", e)
        }
        when (persisted) {
            PersistedEnrollment.Empty -> null
            is PersistedEnrollment.Present -> persisted.record
            PersistedEnrollment.Unreadable ->
                throw EnrollmentDataUnreadableException("Stored enrollment data was corrupted.")
        }
    }
}
