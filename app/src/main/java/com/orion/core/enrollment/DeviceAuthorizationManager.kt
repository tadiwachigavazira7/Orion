// ============================================================
// core/enrollment/DeviceAuthorizationManager.kt
// Single source of truth for "may this installation run Orion right now?".
// Pure Kotlin + coroutines (no Android imports) so every timing rule is
// unit-testable on the JVM with virtual time.
// ============================================================
package com.orion.core.enrollment

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Timing policy for verification triggers. Defaults are the approved product values. */
data class VerificationSchedule(
    /** Periodic verification while the app is in the foreground. */
    val periodicIntervalMs: Long = 15 * 60 * 1000L,
    /** Bounded backoff after connectivity returns: one immediate attempt, then one retry per entry. */
    val connectivityRetryDelaysMs: List<Long> = listOf(2_000L, 5_000L, 15_000L),
    /**
     * Upper bound on a single deadline-timer sleep. The deadline itself is computed
     * from the monotonic clock; chunking bounds how late the scheduled transition can
     * be if the platform's delay() clock pauses (deep sleep) while elapsedRealtime keeps
     * counting. Guaranteed bounds: [DeviceAuthorizationManager.isAccessGranted] (checked
     * before every lookup/navigation start) and every foreground start re-check the
     * monotonic deadline directly, so a late timer can only delay the published state
     * change by at most this interval after the device wakes while the app is visible;
     * it can never let a new find/navigation start past the deadline.
     */
    val maxDeadlineCheckIntervalMs: Long = 60_000L
)

/**
 * Coordinates enrollment, verification triggers, the offline allowance, and its
 * deadline.
 *
 * Rules enforced here:
 *  * Explicit denials (revoked / invalid credential / unknown device) block
 *    immediately, override any remaining offline allowance, and are persisted
 *    (allowance cleared) so a restart while offline cannot restore access.
 *  * Verification unavailable is not a denial: access continues only while the
 *    server-issued allowance is valid; it is never extended locally.
 *  * The allowance ends exactly at its monotonic deadline (a scheduled
 *    transition, not only on the next check).
 *  * Verification is single-flight; results are applied only if no newer
 *    denial / credential change happened since the request started (generation).
 *
 * [scope] must be owned by a lifecycle owner (e.g. a ViewModel's scope); this
 * class never creates an unowned scope.
 */
class DeviceAuthorizationManager(
    private val store: EnrollmentStore,
    private val verifier: EnrollmentVerifier,
    private val deviceIdProvider: DeviceIdProvider,
    private val clock: MonotonicClock,
    private val bootIdentity: BootIdentityProvider,
    private val scope: CoroutineScope,
    private val schedule: VerificationSchedule = VerificationSchedule()
) : DeviceAccessGate {

    private val _state = MutableStateFlow<DeviceAuthorizationState>(DeviceAuthorizationState.Checking)
    override val state: StateFlow<DeviceAuthorizationState> = _state.asStateFlow()

    // ---- guarded by stateLock ----
    private val stateLock = Mutex()
    private var record: StoredEnrollment? = null
    private var generation = 0L
    private var deadlineJob: Job? = null
    /** The in-memory [record] differs from what was last successfully persisted; retried on later triggers. */
    private var persistPending = false

    /** The in-process allowance currently backing Authorized/OfflineAuthorized (read lock-free by [isAccessGranted]). */
    @Volatile
    private var allowance: OfflineAuthorization? = null

    // ---- single-flight verification ----
    private val verifyLock = Mutex()
    private var inFlight: Deferred<VerificationResult?>? = null

    private val loaded = CompletableDeferred<Unit>()

    init {
        scope.launch { loadInitialState() }
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    override fun isAccessGranted(): Boolean {
        if (!_state.value.grantsAccess) return false
        val current = allowance ?: return false
        if (current.isValidInProcess(clock.elapsedRealtimeMs())) return true
        // Deadline passed but the scheduled transition hasn't run yet (e.g. the
        // delay clock paused in deep sleep): deny now and publish the transition.
        scope.launch { expireIfElapsed() }
        return false
    }

    /**
     * Runs the foreground triggers until cancelled: an immediate verification
     * (covers app launch and return to foreground), periodic verification, and
     * bounded-backoff verification when [connectivity] returns (false -> true).
     * Call from a lifecycle-bound collector (e.g. repeatOnLifecycle(STARTED)).
     */
    suspend fun runWhileForeground(connectivity: Flow<Boolean>) {
        loaded.await()
        // Re-check the deadline against the monotonic clock on every foreground start: the
        // deadline timer's delay() may have been paused (deep sleep) while elapsedRealtime
        // kept counting.
        expireIfElapsed()
        stateLock.withLock { retryPendingPersistence() }
        coroutineScope {
            launch {
                while (true) {
                    verifyNow()
                    delay(schedule.periodicIntervalMs)
                }
            }
            launch {
                connectivity
                    .distinctUntilChanged()
                    .drop(1) // the current value is not a "return"; only later transitions to true are
                    .filter { it }
                    .collectLatest { verifyWithBackoff() }
            }
        }
    }

    /** User-initiated "check again" (e.g. from the connection-required screen). */
    fun requestVerification() {
        scope.launch { verifyNow() }
    }

    /**
     * Verifies the stored credential once. Joins an already in-flight request
     * instead of starting an overlapping one. Returns `null` when verification
     * is not applicable in the current state (nothing stored, or an enrollment
     * attempt is in progress / has superseded the stored credential).
     */
    suspend fun verifyNow(): VerificationResult? {
        loaded.await()
        val request = verifyLock.withLock {
            inFlight?.takeIf { it.isActive }
                ?: scope.async { performVerification() }.also { inFlight = it }
        }
        return request.await()
    }

    /**
     * Enrollment / controlled re-enrollment. Only offered from states where the
     * employee is shown the enrollment form; only an Approved result that was
     * successfully saved replaces the stored credential.
     */
    suspend fun enroll(organizationCode: String, siteCode: String, enrollmentAuthorization: String) {
        loaded.await()
        val providedDeviceId = deviceIdProvider.currentDeviceId()?.takeIf { it.isNotBlank() }
        val deviceId: String = stateLock.withLock {
            if (!_state.value.allowsEnrollment()) return
            if (providedDeviceId == null) {
                _state.value = DeviceAuthorizationState.DeviceIdUnavailable
                return
            }
            // Any verification already in flight for the old credential is now stale.
            generation++
            _state.value = DeviceAuthorizationState.Enrolling
            providedDeviceId
        }

        val request = EnrollmentRequest(
            organizationCode = organizationCode.trim(),
            siteCode = siteCode.trim(),
            deviceId = deviceId,
            enrollmentAuthorization = enrollmentAuthorization.trim()
        )
        val result = try {
            verifier.enroll(request)
        } catch (e: CancellationException) {
            // The owning scope is going away (it is the same scope this manager lives in),
            // so there is no state left to publish.
            throw e
        } catch (e: Exception) {
            // Verifier implementations map their own failures; this only guards against
            // an unexpected bug escaping as an exception. The type name, not the message,
            // is surfaced so nothing request-derived can leak into the UI.
            EnrollmentResult.RecoveryRequired("$OUTCOME_UNKNOWN (${e::class.simpleName})")
        }

        stateLock.withLock {
            if (_state.value != DeviceAuthorizationState.Enrolling) {
                // Defensive (no current path leaves Enrolling while a request is in flight):
                // an approval must never be silently dropped, because the server has already
                // consumed the authorization and activated the device.
                if (result is EnrollmentResult.Approved) {
                    _state.value = DeviceAuthorizationState.RecoveryRequired("$OUTCOME_UNKNOWN.")
                }
                return
            }
            _state.value = when (result) {
                is EnrollmentResult.Approved -> applyApprovedEnrollment(result)
                is EnrollmentResult.Rejected -> DeviceAuthorizationState.EnrollmentRejected(result.reason)
                is EnrollmentResult.RecoveryRequired -> DeviceAuthorizationState.RecoveryRequired(result.reason)
                is EnrollmentResult.Failure -> DeviceAuthorizationState.EnrollmentFailed(result.reason)
            }
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private suspend fun loadInitialState() {
        val stored = try {
            store.load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // EnrollmentDataUnreadableException (corruption / Keystore loss / I/O) or any other
            // read failure: "broken" must not look like "never enrolled" (§22). A possibly
            // persisted denial is therefore never lost silently; recovery is re-enrollment
            // after an administrator reset, and a successful save replaces the bad data.
            stateLock.withLock {
                _state.value = DeviceAuthorizationState.RecoveryRequired(
                    if (e is SecureStorageUnavailableException) SECURE_STORAGE_UNAVAILABLE else UNREADABLE_STORE
                )
            }
            loaded.complete(Unit)
            return
        }

        stateLock.withLock {
            record = stored
            _state.value = when {
                stored == null -> notEnrolledState()
                stored.denial != null -> stored.denial.toState()
                else -> {
                    val restored = stored.offlineAuthorization?.takeIf {
                        it.isValidAfterReload(clock.elapsedRealtimeMs(), bootIdentity.currentBootCount())
                    }
                    if (restored != null) {
                        grantFrom(restored)
                        DeviceAuthorizationState.OfflineAuthorized(
                            offlineAccessEndsAtElapsedRealtimeMs = restored.expiresAtElapsedRealtimeMs,
                            lastVerificationProblem = null
                        )
                    } else {
                        // Legacy credential, another boot, or an elapsed allowance:
                        // online verification is required before access.
                        DeviceAuthorizationState.Checking
                    }
                }
            }
        }
        loaded.complete(Unit)
    }

    private suspend fun performVerification(): VerificationResult? {
        val (requestGeneration, credential) = stateLock.withLock {
            retryPendingPersistence()
            val current = record ?: return null
            if (!_state.value.allowsVerification()) return null
            generation to current.credential
        }

        val result = try {
            verifier.verify(credential)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Unexpected bug in a verifier implementation: never treat as a denial.
            VerificationResult.Unavailable("Verification failed unexpectedly (${e::class.simpleName}).")
        }

        stateLock.withLock {
            if (requestGeneration != generation || record?.credential != credential) {
                return result // stale: a newer denial / credential change already happened
            }
            applyVerification(result, credential)
        }
        return result
    }

    /** Must be called with [stateLock] held. */
    private suspend fun applyVerification(result: VerificationResult, credential: EnrollmentCredential) {
        when (result) {
            is VerificationResult.Authorized -> {
                val authorization = OfflineAuthorization.anchor(
                    window = result.window,
                    nowElapsedRealtimeMs = clock.elapsedRealtimeMs(),
                    bootCount = bootIdentity.currentBootCount()
                )
                val updated = StoredEnrollment(credential, authorization, denial = null)
                record = updated
                persistBestEffort(updated)
                grantFrom(authorization)
                _state.value = DeviceAuthorizationState.Authorized(authorization.expiresAtElapsedRealtimeMs)
            }

            VerificationResult.Revoked -> applyDenial(DenialReason.REVOKED, credential)
            VerificationResult.InvalidCredential -> applyDenial(DenialReason.INVALID_CREDENTIAL, credential)
            VerificationResult.UnknownDevice -> applyDenial(DenialReason.UNKNOWN_DEVICE, credential)

            is VerificationResult.Unavailable -> {
                val current = _state.value
                if (current.isExplicitDenial()) return // unavailability never lifts a denial
                val stillValid = allowance?.takeIf { it.isValidInProcess(clock.elapsedRealtimeMs()) }
                _state.value = if (stillValid != null) {
                    DeviceAuthorizationState.OfflineAuthorized(
                        offlineAccessEndsAtElapsedRealtimeMs = stillValid.expiresAtElapsedRealtimeMs,
                        lastVerificationProblem = result.reason
                    )
                } else {
                    revokeAllowance()
                    DeviceAuthorizationState.ConnectionRequired
                }
            }
        }
    }

    /** Must be called with [stateLock] held. */
    private suspend fun applyDenial(reason: DenialReason, credential: EnrollmentCredential) {
        generation++
        revokeAllowance()
        val updated = StoredEnrollment(credential, offlineAuthorization = null, denial = reason)
        record = updated
        persistBestEffort(updated)
        _state.value = reason.toState()
    }

    /** Must be called with [stateLock] held. */
    private suspend fun applyApprovedEnrollment(result: EnrollmentResult.Approved): DeviceAuthorizationState {
        val authorization = OfflineAuthorization.anchor(
            window = result.window,
            nowElapsedRealtimeMs = clock.elapsedRealtimeMs(),
            bootCount = bootIdentity.currentBootCount()
        )
        val updated = StoredEnrollment(result.credential, authorization, denial = null)
        try {
            store.save(updated)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecureStorageUnavailableException) {
            // An administrator reset alone cannot fix this: the next approval would fail to
            // save again. Say what actually helps; never delete keys/data automatically.
            return DeviceAuthorizationState.RecoveryRequired(SECURE_STORAGE_UNAVAILABLE)
        } catch (e: Exception) {
            // The server has consumed the authorization and activated the device, but
            // this installation does not hold the credential. Retrying would get 409,
            // so this is an explicit recovery state, not a silent failure.
            return DeviceAuthorizationState.RecoveryRequired(
                "Enrollment was approved but could not be saved on this device " +
                    "(${e::class.simpleName}). Ask an administrator to reset this device, then enroll again."
            )
        }
        generation++
        record = updated
        persistPending = false
        grantFrom(authorization)
        return DeviceAuthorizationState.Authorized(authorization.expiresAtElapsedRealtimeMs)
    }

    /**
     * Persists a verification outcome. The in-memory state already reflects the
     * server's answer and is never rolled back because a write failed. A failed
     * write is recorded in [persistPending] and retried on every later verification
     * trigger, foreground start, and allowance expiry, so any later successful write
     * stores the latest outcome (including a denial, which clears the allowance).
     *
     * Remaining gap (documented in CLAUDE.md §16): if the process dies before any
     * retry succeeds, a restart in the same boot could restore the previously stored
     * allowance until its own deadline or the next successful check.
     *
     * Must be called with [stateLock] held.
     */
    private suspend fun persistBestEffort(updated: StoredEnrollment) {
        persistPending = try {
            store.save(updated)
            false
        } catch (e: CancellationException) {
            persistPending = true
            throw e
        } catch (e: Exception) {
            // Not rethrown; see KDoc. Retried later. No credential data is logged.
            true
        }
    }

    /** Must be called with [stateLock] held. */
    private suspend fun retryPendingPersistence() {
        if (!persistPending) return
        val current = record ?: return
        persistBestEffort(current)
    }

    private suspend fun verifyWithBackoff() {
        val delays = schedule.connectivityRetryDelaysMs
        for (attempt in 0..delays.size) {
            val result = verifyNow()
            if (result !is VerificationResult.Unavailable) return
            if (attempt == delays.size) return
            delay(delays[attempt])
        }
    }

    /** Must be called with [stateLock] held. */
    private fun grantFrom(authorization: OfflineAuthorization) {
        allowance = authorization
        deadlineJob?.cancel()
        deadlineJob = scope.launch {
            while (true) {
                val remaining = authorization.expiresAtElapsedRealtimeMs - clock.elapsedRealtimeMs()
                if (remaining <= 0) break
                delay(minOf(remaining, schedule.maxDeadlineCheckIntervalMs))
            }
            onAllowanceElapsed(authorization)
        }
    }

    /** Must be called with [stateLock] held. */
    private fun revokeAllowance() {
        allowance = null
        deadlineJob?.cancel()
        deadlineJob = null
    }

    private suspend fun onAllowanceElapsed(authorization: OfflineAuthorization) {
        stateLock.withLock {
            retryPendingPersistence()
            if (allowance != authorization) return
            allowance = null
            deadlineJob = null
            if (_state.value.grantsAccess) {
                // Credential is kept: a successful verification restores access.
                _state.value = DeviceAuthorizationState.ConnectionRequired
            }
        }
    }

    private suspend fun expireIfElapsed() {
        val current = allowance ?: return
        if (!current.isValidInProcess(clock.elapsedRealtimeMs())) onAllowanceElapsed(current)
    }

    private fun notEnrolledState(): DeviceAuthorizationState =
        if (deviceIdProvider.currentDeviceId().isNullOrBlank()) {
            DeviceAuthorizationState.DeviceIdUnavailable
        } else {
            DeviceAuthorizationState.NotEnrolled
        }

    private companion object {
        const val SECURE_STORAGE_UNAVAILABLE =
            "Secure storage on this device is unavailable. Clear Orion's app data or reinstall, " +
                "then ask an administrator to reset this device."
        const val UNREADABLE_STORE =
            "Enrollment data on this device could not be read. Ask an administrator to reset " +
                "this device, then enroll again."

        const val OUTCOME_UNKNOWN =
            "The enrollment outcome could not be confirmed. Try again; if the device is reported " +
                "as already enrolled, ask an administrator to reset this device"
    }
}

private fun DenialReason.toState(): DeviceAuthorizationState = when (this) {
    DenialReason.REVOKED -> DeviceAuthorizationState.Revoked
    DenialReason.INVALID_CREDENTIAL -> DeviceAuthorizationState.InvalidCredential
    DenialReason.UNKNOWN_DEVICE -> DeviceAuthorizationState.UnknownDevice
}

private fun DeviceAuthorizationState.isExplicitDenial(): Boolean =
    this is DeviceAuthorizationState.Revoked ||
        this is DeviceAuthorizationState.InvalidCredential ||
        this is DeviceAuthorizationState.UnknownDevice

/** States in which the stored credential is (re)verified. Paused while an enrollment attempt owns the screen. */
private fun DeviceAuthorizationState.allowsVerification(): Boolean = when (this) {
    DeviceAuthorizationState.Checking,
    is DeviceAuthorizationState.Authorized,
    is DeviceAuthorizationState.OfflineAuthorized,
    DeviceAuthorizationState.ConnectionRequired,
    DeviceAuthorizationState.Revoked, // so an administrator reset is noticed (-> InvalidCredential)
    DeviceAuthorizationState.InvalidCredential,
    DeviceAuthorizationState.UnknownDevice -> true

    DeviceAuthorizationState.NotEnrolled,
    DeviceAuthorizationState.DeviceIdUnavailable,
    DeviceAuthorizationState.Enrolling,
    is DeviceAuthorizationState.EnrollmentRejected,
    is DeviceAuthorizationState.EnrollmentFailed,
    is DeviceAuthorizationState.RecoveryRequired -> false
}

/** States that show the enrollment form. Revoked is excluded: an administrator must reset first. */
private fun DeviceAuthorizationState.allowsEnrollment(): Boolean = when (this) {
    DeviceAuthorizationState.NotEnrolled,
    DeviceAuthorizationState.DeviceIdUnavailable,
    is DeviceAuthorizationState.EnrollmentRejected,
    is DeviceAuthorizationState.EnrollmentFailed,
    is DeviceAuthorizationState.RecoveryRequired,
    DeviceAuthorizationState.InvalidCredential,
    DeviceAuthorizationState.UnknownDevice -> true

    DeviceAuthorizationState.Checking,
    DeviceAuthorizationState.Enrolling,
    is DeviceAuthorizationState.Authorized,
    is DeviceAuthorizationState.OfflineAuthorized,
    DeviceAuthorizationState.ConnectionRequired,
    DeviceAuthorizationState.Revoked -> false
}
