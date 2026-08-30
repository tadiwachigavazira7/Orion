// ============================================================
// core/enrollment/Enrollment.kt
// Pure domain models for device enrollment. No Android/platform imports.
//
// Product scope (do not expand): the PDT does NOT authenticate an employee.
// There is no employee login, ID, account, or session anywhere in this
// feature. The only job of this system is to verify and persist that THIS
// Orion installation is authorized for a given Organization -> Store/Site ->
// Device, once, on first launch, then gate subsequent launches on that
// local state.
// ============================================================
package com.orion.core.enrollment

/**
 * A locally-persisted proof that this device installation is authorized to
 * run Orion for a specific organization/site/device. Contains no employee
 * data of any kind.
 */
data class EnrollmentCredential(
    val organizationId: String,
    val siteId: String,
    val deviceId: String,
    val token: String,       // opaque, backend-issued — do not assume or invent its format
    val issuedAt: Long,      // epoch millis
    val expiresAt: Long?     // epoch millis; null = does not expire
) {
    // Redact the raw token in case this is ever logged or printed — defense
    // in depth, not a substitute for keeping tokens out of logs entirely.
    override fun toString(): String =
        "EnrollmentCredential(organizationId=$organizationId, siteId=$siteId, " +
            "deviceId=$deviceId, token=<redacted>, issuedAt=$issuedAt, expiresAt=$expiresAt)"
}

/** Input to the enrollment verification call. Codes are human-entered; deviceId is device-derived. */
data class EnrollmentRequest(
    val organizationCode: String,
    val siteCode: String,
    val deviceId: String
)

/** Outcome of an enrollment verification attempt against the backend. */
sealed interface EnrollmentResult {
    data class Approved(val credential: EnrollmentCredential) : EnrollmentResult
    data class Rejected(val reason: String) : EnrollmentResult   // backend explicitly denied this org/site/device combo
    data class Failure(val reason: String) : EnrollmentResult    // network/backend error, or "not configured"
}

/**
 * The backend seam for enrollment verification. Real implementations must be
 * supplied by integrating with the customer's enterprise enrollment API —
 * see [com.orion.data.enrollment.UnconfiguredEnrollmentVerifier] for the
 * honest placeholder shipped today.
 */
interface EnrollmentVerifier {
    suspend fun verify(request: EnrollmentRequest): EnrollmentResult
}

/** Local persistence seam for the enrollment credential. Platform-specific implementations live in data.enrollment. */
interface EnrollmentStore {
    suspend fun save(credential: EnrollmentCredential)
    suspend fun load(): EnrollmentCredential?
    suspend fun clear()
}

/** Vendor-neutral seam for obtaining a stable device identifier (not an RFID reader identifier). */
interface DeviceIdProvider {
    fun currentDeviceId(): String
}

/**
 * Current enrollment status of this installation, derived by comparing a
 * locally-stored expiry timestamp against the current time. Distinguishing
 * [Expired] from [NotEnrolled] lets the UI say "your enrollment expired"
 * rather than "this device isn't enrolled".
 */
sealed interface EnrollmentStatus {
    data class Enrolled(val credential: EnrollmentCredential) : EnrollmentStatus
    data object NotEnrolled : EnrollmentStatus
    data class Expired(val credential: EnrollmentCredential) : EnrollmentStatus
}
