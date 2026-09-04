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
import com.orion.core.enrollment.EnrollmentCredential
import com.orion.core.enrollment.EnrollmentStore
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

private const val KEYSET_NAME = "orion_enrollment_keyset"
private const val KEYSET_PREF_FILE_NAME = "orion_enrollment_keyset_prefs"
private const val MASTER_KEY_URI = "android-keystore://orion_enrollment_master_key"
private const val DATASTORE_FILE_NAME = "orion_enrollment_secure.pb"

/**
 * Serializes [EnrollmentCredential] (or `null`, meaning "not enrolled" -- this
 * IS the [defaultValue]) to/from a plain byte stream.
 *
 * No JSON/protobuf dependency exists in this project, so the 6 fields are
 * encoded manually. `organizationId`/`siteId`/`deviceId`/`token` use a
 * length-prefixed UTF-8 encoding (4-byte length + raw UTF-8 bytes) rather than
 * `DataOutputStream.writeUTF`/`readUTF`, which caps encoded length at 65535
 * bytes -- `token` is documented on [EnrollmentCredential] as opaque,
 * backend-issued, with no assumed format or length.
 *
 * `internal` (not `private`) so this file's byte encode/decode round-trip can
 * be covered by a plain JVM unit test in this module's test source set --
 * this is the one piece of the store that's meaningfully unit-testable
 * without Android/DataStore/Tink dependencies.
 */
internal object EnrollmentCredentialSerializer : Serializer<EnrollmentCredential?> {

    override val defaultValue: EnrollmentCredential? = null

    override suspend fun readFrom(input: InputStream): EnrollmentCredential? {
        try {
            val dataIn = DataInputStream(input)
            val hasCredential = dataIn.readBoolean()
            if (!hasCredential) return null

            val organizationId = readLengthPrefixedString(dataIn)
            val siteId = readLengthPrefixedString(dataIn)
            val deviceId = readLengthPrefixedString(dataIn)
            val token = readLengthPrefixedString(dataIn)
            val issuedAt = dataIn.readLong()
            val hasExpiresAt = dataIn.readBoolean()
            val expiresAt = if (hasExpiresAt) dataIn.readLong() else null

            return EnrollmentCredential(
                organizationId = organizationId,
                siteId = siteId,
                deviceId = deviceId,
                token = token,
                issuedAt = issuedAt,
                expiresAt = expiresAt
            )
        } catch (e: EOFException) {
            // Malformed/truncated bytes. Surfaced as CorruptionException (which
            // extends IOException) so the ReplaceFileCorruptionHandler wired up
            // in DataStoreEnrollmentStore.buildDataStore() can transparently
            // reset this back to defaultValue (null) instead of crashing every
            // read.
            throw CorruptionException("Malformed EnrollmentCredential bytes", e)
        } catch (e: IOException) {
            throw CorruptionException("Malformed EnrollmentCredential bytes", e)
        }
    }

    override suspend fun writeTo(t: EnrollmentCredential?, output: OutputStream) {
        val dataOut = DataOutputStream(output)
        if (t == null) {
            dataOut.writeBoolean(false)
            dataOut.flush()
            return
        }

        dataOut.writeBoolean(true)
        writeLengthPrefixedString(dataOut, t.organizationId)
        writeLengthPrefixedString(dataOut, t.siteId)
        writeLengthPrefixedString(dataOut, t.deviceId)
        writeLengthPrefixedString(dataOut, t.token)
        dataOut.writeLong(t.issuedAt)
        val expiresAt = t.expiresAt
        if (expiresAt != null) {
            dataOut.writeBoolean(true)
            dataOut.writeLong(expiresAt)
        } else {
            dataOut.writeBoolean(false)
        }
        dataOut.flush()
    }

    private fun writeLengthPrefixedString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readLengthPrefixedString(input: DataInputStream): String {
        val length = input.readInt()
        if (length < 0) throw IOException("Negative length-prefixed string length: $length")
        // A torn/partially-written file (e.g. process killed mid-write) can produce a
        // large positive length up to Int.MAX_VALUE. Without this cap, `ByteArray(length)`
        // can throw OutOfMemoryError -- an Error, not an Exception -- before readFully()
        // ever gets a chance to throw the EOFException/IOException that the caller above
        // already converts to CorruptionException (letting ReplaceFileCorruptionHandler
        // degrade gracefully to "not enrolled" instead of crashing). The cap is set well
        // above any realistic field value -- organizationId/siteId/deviceId are short
        // identifiers, and the existing "token longer than 65535 bytes" test round-trips a
        // 100,000-byte token -- while still ruling out multi-hundred-MB/GB allocations from
        // a corrupted length prefix.
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
    private val dataStore: DataStore<EnrollmentCredential?>
        get() = getOrCreateDataStore(context.applicationContext)

    companion object {
        @Volatile
        private var instance: DataStore<EnrollmentCredential?>? = null

        private fun getOrCreateDataStore(appContext: Context): DataStore<EnrollmentCredential?> {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val built = buildDataStore(appContext)
                instance = built
                return built
            }
        }

        private fun buildDataStore(appContext: Context): DataStore<EnrollmentCredential?> {
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
                wrappedSerializer = EnrollmentCredentialSerializer,
                // Unique name for this DataStore file -- prevents ciphertext from this file
                // being swapped into a different encrypted DataStore undetected.
                associatedData = DATASTORE_FILE_NAME.encodeToByteArray()
            )

            return DataStoreFactory.create(
                serializer = aeadSerializer,
                corruptionHandler = ReplaceFileCorruptionHandler { null },
                produceFile = { File(appContext.filesDir, "datastore/$DATASTORE_FILE_NAME") }
            )
        }
    }

    override suspend fun save(credential: EnrollmentCredential) = withContext(Dispatchers.IO) {
        // Deliberate: a Keystore/Tink failure here (e.g. corrupted keystore) is allowed
        // to propagate as an uncaught exception on save, since EnrollDeviceUseCase
        // already wraps it into an explicit EnrollmentResult.Failure rather than
        // silently losing the just-approved enrollment. Matches the prior
        // EncryptedPrefsEnrollmentStore's documented decision. AeadSerializer.writeTo
        // similarly does not catch GeneralSecurityException from aead.encrypt() --
        // it propagates raw, consistent with this.
        dataStore.updateData { credential }
        Unit
    }

    override suspend fun load(): EnrollmentCredential? = withContext(Dispatchers.IO) {
        try {
            dataStore.data.first()
        } catch (e: IOException) {
            // Safety net for DataStore file I/O failures. Most corruption cases never
            // reach this catch because AeadSerializer.readFrom's CorruptionException is
            // already handled transparently by the ReplaceFileCorruptionHandler wired
            // up in buildDataStore(), which resets the file back to defaultValue (null).
            null
        } catch (e: GeneralSecurityException) {
            // Covers the lazy Tink/Keystore-open step (buildDataStore(), accessed via
            // the `dataStore` property above) failing -- e.g. keystore corruption/reset
            // -- before any DataStore file I/O happens. Treat this installation as "not
            // enrolled" rather than crashing the app on every launch. Narrowly scoped to
            // this open/read path, not a blanket catch-all, so it cannot mask unrelated
            // bugs in save()/clear(). Same rationale as the prior
            // EncryptedPrefsEnrollmentStore's openPrefs() catch.
            null
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        try {
            dataStore.updateData { null }
        } catch (e: IOException) {
            // Nothing usable to clear if the store can't even be opened/read; see
            // load()'s comment above for why this narrow catch is intentional.
        } catch (e: GeneralSecurityException) {
            // See load()'s comment above.
        }
        Unit
    }
}
