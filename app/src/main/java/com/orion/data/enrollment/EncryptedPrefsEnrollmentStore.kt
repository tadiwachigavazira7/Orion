// ============================================================
// data/enrollment/EncryptedPrefsEnrollmentStore.kt
// Android-specific EnrollmentStore backed by Jetpack Security's
// EncryptedSharedPreferences (AES256-GCM master key from the Android
// Keystore). This package is allowed to import android.*/androidx.* per
// the architecture in CLAUDE.md §6/§20 — core.enrollment must not.
// ============================================================
package com.orion.data.enrollment

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.orion.core.enrollment.EnrollmentCredential
import com.orion.core.enrollment.EnrollmentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val PREFS_FILE_NAME = "orion_enrollment_secure"

private const val KEY_ORGANIZATION_ID = "organizationId"
private const val KEY_SITE_ID = "siteId"
private const val KEY_DEVICE_ID = "deviceId"
private const val KEY_TOKEN = "token"
private const val KEY_ISSUED_AT = "issuedAt"
private const val KEY_EXPIRES_AT = "expiresAt"

/** Sentinel distinguishing "no expiry" from "absent/corrupt" in the underlying Long-only API. */
private const val NO_EXPIRY = -1L
private const val EXPIRES_AT_ABSENT = -2L

class EncryptedPrefsEnrollmentStore(private val context: Context) : EnrollmentStore {

    private fun openPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override suspend fun save(credential: EnrollmentCredential) = withContext(Dispatchers.IO) {
        // Deliberate: a Keystore/EncryptedSharedPreferences failure here (e.g. corrupted
        // keystore) is allowed to propagate as an uncaught exception on save, since
        // EnrollDeviceUseCase already wraps it into an explicit EnrollmentResult.Failure
        // rather than silently losing the just-approved enrollment.
        val prefs = openPrefs()
        prefs.edit()
            .putString(KEY_ORGANIZATION_ID, credential.organizationId)
            .putString(KEY_SITE_ID, credential.siteId)
            .putString(KEY_DEVICE_ID, credential.deviceId)
            .putString(KEY_TOKEN, credential.token)
            .putLong(KEY_ISSUED_AT, credential.issuedAt)
            .putLong(KEY_EXPIRES_AT, credential.expiresAt ?: NO_EXPIRY)
            .apply()
    }

    override suspend fun load(): EnrollmentCredential? = withContext(Dispatchers.IO) {
        val prefs = try {
            openPrefs()
        } catch (e: Exception) {
            // Deliberate, documented decision: if the Keystore-backed prefs themselves
            // cannot be opened (e.g. keystore corruption/reset), treat this installation
            // as "not enrolled" rather than crashing the app on every launch. This is
            // narrowly scoped to the store-open call, not a blanket catch-all, so it
            // cannot mask unrelated bugs elsewhere in save()/clear().
            return@withContext null
        }

        val organizationId = prefs.getString(KEY_ORGANIZATION_ID, null)
        val siteId = prefs.getString(KEY_SITE_ID, null)
        val deviceId = prefs.getString(KEY_DEVICE_ID, null)
        val token = prefs.getString(KEY_TOKEN, null)
        val hasIssuedAt = prefs.contains(KEY_ISSUED_AT)
        val issuedAt = prefs.getLong(KEY_ISSUED_AT, 0L)
        val expiresAtRaw = prefs.getLong(KEY_EXPIRES_AT, EXPIRES_AT_ABSENT)

        // Partial/corrupt state (any required field missing) is treated as "not enrolled"
        // rather than crashing or returning a half-populated credential.
        if (organizationId == null || siteId == null || deviceId == null || token == null || !hasIssuedAt || expiresAtRaw == EXPIRES_AT_ABSENT) {
            return@withContext null
        }

        val expiresAt = if (expiresAtRaw == NO_EXPIRY) null else expiresAtRaw

        EnrollmentCredential(
            organizationId = organizationId,
            siteId = siteId,
            deviceId = deviceId,
            token = token,
            issuedAt = issuedAt,
            expiresAt = expiresAt
        )
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        val prefs = try {
            openPrefs()
        } catch (e: Exception) {
            // Nothing usable to clear if the store can't even be opened; see load()'s
            // comment above for why this narrow catch is intentional.
            return@withContext
        }
        prefs.edit().clear().apply()
    }
}
