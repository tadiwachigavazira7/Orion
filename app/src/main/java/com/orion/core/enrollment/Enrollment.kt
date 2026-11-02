// ============================================================
// core/enrollment/Enrollment.kt
// Pure domain models and seams for device enrollment + verification.
// No Android/platform/HTTP imports.
//
// Product scope (do not expand): the PDT does NOT authenticate an employee.
// There is no employee login, ID, account, or session anywhere in this
// feature. This system answers one question: "is THIS Orion installation
// trusted to run for a given Organization -> Site -> Device right now?"
//
// Two deliberately separate things are persisted:
//  * EnrollmentCredential  -- durable proof of enrollment; never expires on
//    the device. Only the backend can invalidate it (revoke / reset).
//  * OfflineAuthorization  -- a time-limited, server-issued allowance to keep
//    working without connectivity. Anchored to the monotonic clock of the
//    boot in which it was received; never extended by the device.
// ============================================================
package com.orion.core.enrollment

/**
 * Durable, backend-issued proof that this installation is enrolled for a
 * specific organization/site/device. Has no expiry of its own: whether the
 * device may run right now is decided by verification + [OfflineAuthorization].
 */
data class EnrollmentCredential(
    val organizationCode: String,
    val siteCode: String,
    val deviceId: String,
    val token: String // opaque, backend-issued -- do not assume or invent its format
) {
    // Redact the raw token in case this is ever logged or printed -- defense
    // in depth, not a substitute for keeping tokens out of logs entirely.
    override fun toString(): String =
        "EnrollmentCredential(organizationCode=$organizationCode, siteCode=$siteCode, " +
            "deviceId=$deviceId, token=<redacted>)"
}

/**
 * The server-determined offline window carried by a successful enroll (201) or
 * verify (200) response. Wall-clock instants from the SERVER clock, in epoch
 * millis; only their difference ([durationMs]) is used for gating on device.
 */
data class ServerAuthorizationWindow(
    val serverTimeEpochMs: Long,
    val offlineValidUntilEpochMs: Long
) {
    init {
        require(offlineValidUntilEpochMs > serverTimeEpochMs) {
            "offline_valid_until must be after server_time"
        }
    }

    val durationMs: Long get() = offlineValidUntilEpochMs - serverTimeEpochMs
}

/**
 * Time-limited offline allowance, anchored to the device's monotonic clock
 * ([MonotonicClock.elapsedRealtimeMs]) at the moment the server response was
 * received, and tagged with the boot it was received in ([bootCount]).
 *
 * The allowance is exactly the server-provided window duration starting at
 * [receivedAtElapsedRealtimeMs]. Wall-clock changes on the device cannot
 * extend it, because wall-clock time is never consulted.
 */
data class OfflineAuthorization(
    val serverTimeEpochMs: Long,
    val offlineValidUntilEpochMs: Long,
    val receivedAtElapsedRealtimeMs: Long,
    /** Boot identity at receipt; `null` = unreadable at receipt, which voids it after any reload. */
    val bootCount: Int?
) {
    /** Server-determined allowance duration (`offline_valid_until - server_time`). */
    val allowanceDurationMs: Long get() = offlineValidUntilEpochMs - serverTimeEpochMs

    /** Monotonic-clock instant (same boot only) at which the allowance ends. */
    val expiresAtElapsedRealtimeMs: Long get() = receivedAtElapsedRealtimeMs + allowanceDurationMs

    /**
     * Validity within the process that received it. A process cannot outlive a
     * boot, and elapsedRealtime is monotonic within a boot, so only the window
     * is checked (plus a defensive "clock went backwards" check).
     */
    fun isValidInProcess(nowElapsedRealtimeMs: Long): Boolean =
        allowanceDurationMs > 0 &&
            nowElapsedRealtimeMs >= receivedAtElapsedRealtimeMs &&
            nowElapsedRealtimeMs < expiresAtElapsedRealtimeMs

    /**
     * Validity after being re-loaded from storage (e.g. a process restart). The
     * elapsedRealtime anchor is only meaningful within the boot it was taken in,
     * so a different or unreadable boot identity voids the allowance.
     */
    fun isValidAfterReload(nowElapsedRealtimeMs: Long, currentBootCount: Int?): Boolean =
        bootCount != null &&
            currentBootCount != null &&
            bootCount == currentBootCount &&
            isValidInProcess(nowElapsedRealtimeMs)

    companion object {
        fun anchor(
            window: ServerAuthorizationWindow,
            nowElapsedRealtimeMs: Long,
            bootCount: Int?
        ) = OfflineAuthorization(
            serverTimeEpochMs = window.serverTimeEpochMs,
            offlineValidUntilEpochMs = window.offlineValidUntilEpochMs,
            receivedAtElapsedRealtimeMs = nowElapsedRealtimeMs,
            bootCount = bootCount
        )
    }
}

/** Explicit denials from the backend. Persisted so a restart while offline cannot restore access. */
enum class DenialReason { REVOKED, INVALID_CREDENTIAL, UNKNOWN_DEVICE }

/**
 * Everything persisted about this installation's enrollment. [offlineAuthorization]
 * is always `null` when [denial] is set.
 */
data class StoredEnrollment(
    val credential: EnrollmentCredential,
    val offlineAuthorization: OfflineAuthorization?,
    val denial: DenialReason?
)

/** Input to an enrollment attempt. Codes and authorization are human-entered; deviceId is device-derived. */
data class EnrollmentRequest(
    val organizationCode: String,
    val siteCode: String,
    val deviceId: String,
    /**
     * The store's reusable enrollment code (the "Enrollment auth. code" field), sent as the
     * contract's `enrollment_authorization`. A secret: never log or display it.
     */
    val enrollmentAuthorization: String
) {
    override fun toString(): String =
        "EnrollmentRequest(organizationCode=$organizationCode, siteCode=$siteCode, " +
            "deviceId=$deviceId, enrollmentAuthorization=<redacted>)"
}

/** Outcome of an enrollment attempt against the backend. */
sealed interface EnrollmentResult {
    data class Approved(
        val credential: EnrollmentCredential,
        val window: ServerAuthorizationWindow
    ) : EnrollmentResult

    /** Backend explicitly rejected the input (bad org/site/authorization). The user may correct and retry. */
    data class Rejected(val reason: String) : EnrollmentResult

    /**
     * The device may already be enrolled server-side (409), or the outcome of an
     * attempt is unknown (response lost after the request was sent, or an
     * approval could not be read/saved). An administrator reset is the reliable
     * way forward; never retried automatically.
     */
    data class RecoveryRequired(val reason: String) : EnrollmentResult

    /** The request did not complete (unreachable, 5xx, not configured). Enrollment requires connectivity. */
    data class Failure(val reason: String) : EnrollmentResult
}

/** Outcome of verifying a stored credential against the backend. */
sealed interface VerificationResult {
    data class Authorized(val window: ServerAuthorizationWindow) : VerificationResult

    /** Explicit denial: wrong credential, or no credential server-side (device reset). */
    data object InvalidCredential : VerificationResult

    /** Explicit denial: credential matches but the device is revoked. */
    data object Revoked : VerificationResult

    /** Explicit denial: the backend does not know this device. */
    data object UnknownDevice : VerificationResult

    /** Verification could not be performed (network, 5xx, malformed, unexpected). NOT a denial. */
    data class Unavailable(val reason: String) : VerificationResult
}

/**
 * Backend seam for enrollment and verification. See
 * [com.orion.data.enrollment.HttpEnrollmentVerifier] (Orion's FastAPI enrollment
 * backend) and [com.orion.data.enrollment.UnconfiguredEnrollmentVerifier]
 * (defensive fallback that never fakes success).
 */
interface EnrollmentVerifier {
    suspend fun enroll(request: EnrollmentRequest): EnrollmentResult
    suspend fun verify(credential: EnrollmentCredential): VerificationResult
}

/** Local persistence seam. Platform-specific implementations live in data.enrollment. */
interface EnrollmentStore {
    /**
     * `null` = nothing stored (not enrolled).
     * @throws EnrollmentDataUnreadableException when enrollment data exists (or existed)
     *   but cannot be read -- "broken" is never reported as "empty" (CLAUDE.md §22).
     * @throws SecureStorageUnavailableException when the device's secure storage itself
     *   (e.g. the Keystore-backed encryption key) cannot be opened.
     */
    suspend fun load(): StoredEnrollment?

    /**
     * Replaces the stored record. Throws on failure, including
     * [SecureStorageUnavailableException] when secure storage cannot be opened.
     */
    suspend fun save(record: StoredEnrollment)
}

/**
 * The device's secure storage (Keystore-backed key) cannot be used. Unlike corrupt
 * data, this is not fixed by re-enrolling: until local app data is cleared (or the app
 * reinstalled) every save fails again. Orion never deletes keys or data automatically.
 */
class SecureStorageUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Stored enrollment data exists but could not be read/decrypted (corruption, Keystore loss, I/O). */
class EnrollmentDataUnreadableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Vendor-neutral seam for a stable device identifier (not an RFID reader
 * identifier). An identifier, not an authentication factor. Returns `null` when
 * the platform does not provide a usable identifier -- never a shared fallback.
 */
interface DeviceIdProvider {
    fun currentDeviceId(): String?
}

/** Monotonic clock that keeps counting in deep sleep and is unaffected by wall-clock changes. */
fun interface MonotonicClock {
    fun elapsedRealtimeMs(): Long
}

/** Identity of the current boot, so a monotonic anchor from another boot is never trusted. */
fun interface BootIdentityProvider {
    /** `null` when the boot identity cannot be read. */
    fun currentBootCount(): Int?
}
