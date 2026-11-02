package com.orion.core.enrollment

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE

/** elapsedRealtime at virtual time 0 -- non-zero so "since boot" and "virtual time" can't be confused. */
private const val BOOT_ELAPSED_AT_START = 50_000_000L
private const val BOOT_COUNT = 7

private const val SERVER_NOW = 1_800_000_000_000L
private val WINDOW_24H = ServerAuthorizationWindow(SERVER_NOW, SERVER_NOW + 24 * HOUR)

private val CREDENTIAL = EnrollmentCredential("ORG", "SITE", "device-1", "secret-token")
private val NEW_CREDENTIAL = EnrollmentCredential("ORG", "SITE", "device-1", "new-secret-token")

/** Simulated device: monotonic clock driven by virtual time, plus knobs for deep sleep and reboots. */
@OptIn(ExperimentalCoroutinesApi::class)
private class TestDevice(private val scheduler: TestCoroutineScheduler) {
    /** Extra elapsedRealtime not seen by the coroutine scheduler (e.g. deep sleep pausing delay()). */
    var unscheduledElapsedMs = 0L
    /** Shifts elapsedRealtime to simulate a different boot (it restarts from ~0 after a reboot). */
    var elapsedBase = BOOT_ELAPSED_AT_START
    var bootCount: Int? = BOOT_COUNT

    val clock = MonotonicClock { elapsedBase + unscheduledElapsedMs + scheduler.currentTime }
    val boot = BootIdentityProvider { bootCount }
    fun now() = clock.elapsedRealtimeMs()
}

private fun authorizationReceivedAt(elapsed: Long, boot: Int? = BOOT_COUNT, window: ServerAuthorizationWindow = WINDOW_24H) =
    OfflineAuthorization.anchor(window, elapsed, boot)

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceAuthorizationManagerTest {

    private fun TestScope.newManager(
        store: FakeEnrollmentStore,
        verifier: FakeEnrollmentVerifier,
        device: TestDevice,
        deviceId: String? = "device-1",
        scope: CoroutineScope = backgroundScope
    ): DeviceAuthorizationManager {
        val manager = DeviceAuthorizationManager(
            store = store,
            verifier = verifier,
            deviceIdProvider = FakeDeviceIdProvider(deviceId),
            clock = device.clock,
            bootIdentity = device.boot,
            scope = scope
        )
        runCurrent() // complete the initial load
        return manager
    }

    /** A child scope that can be cancelled independently, to simulate the process dying. */
    private fun TestScope.processScope() = CoroutineScope(backgroundScope.coroutineContext + Job())

    private fun TestScope.authorizedManager(
        store: FakeEnrollmentStore,
        verifier: FakeEnrollmentVerifier,
        device: TestDevice,
        scope: CoroutineScope = backgroundScope
    ): DeviceAuthorizationManager {
        verifier.onVerify = { VerificationResult.Authorized(WINDOW_24H) }
        val manager = newManager(store, verifier, device, scope = scope)
        launch { manager.verifyNow() }
        runCurrent()
        assertTrue(manager.state.value is DeviceAuthorizationState.Authorized)
        return manager
    }

    // ------------------------------------------------------------------
    // Initial state / device ID
    // ------------------------------------------------------------------

    @Test
    fun `nothing stored with a device id is NotEnrolled`() = runTest {
        val manager = newManager(FakeEnrollmentStore(), FakeEnrollmentVerifier(), TestDevice(testScheduler))

        assertEquals(DeviceAuthorizationState.NotEnrolled, manager.state.value)
        assertFalse(manager.isAccessGranted())
    }

    @Test
    fun `null or blank device id is DeviceIdUnavailable and blocks enrollment`() = runTest {
        for (missing in listOf(null, "", "   ")) {
            val verifier = FakeEnrollmentVerifier(onEnroll = { EnrollmentResult.Approved(CREDENTIAL, WINDOW_24H) })
            val manager = newManager(FakeEnrollmentStore(), verifier, TestDevice(testScheduler), deviceId = missing)

            assertEquals(DeviceAuthorizationState.DeviceIdUnavailable, manager.state.value)

            manager.enroll("ORG", "SITE", "auth")
            assertEquals(DeviceAuthorizationState.DeviceIdUnavailable, manager.state.value)
            assertTrue("enrollment must not be attempted without a device id", verifier.enrollRequests.isEmpty())
        }
    }

    @Test
    fun `legacy credential without authorization metadata requires online verification`() = runTest {
        val store = FakeEnrollmentStore(StoredEnrollment(CREDENTIAL, offlineAuthorization = null, denial = null))
        val verifier = FakeEnrollmentVerifier(onVerify = { VerificationResult.Unavailable("offline") })
        val manager = newManager(store, verifier, TestDevice(testScheduler))

        assertEquals(DeviceAuthorizationState.Checking, manager.state.value)
        assertFalse(manager.isAccessGranted())

        manager.verifyNow()
        assertEquals(DeviceAuthorizationState.ConnectionRequired, manager.state.value)

        verifier.onVerify = { VerificationResult.Authorized(WINDOW_24H) }
        manager.verifyNow()
        assertTrue(manager.state.value is DeviceAuthorizationState.Authorized)
        assertTrue(manager.isAccessGranted())
    }

    // ------------------------------------------------------------------
    // Enrollment
    // ------------------------------------------------------------------

    @Test
    fun `approved enrollment persists credential and anchored authorization and grants access`() = runTest {
        val device = TestDevice(testScheduler)
        val store = FakeEnrollmentStore()
        val verifier = FakeEnrollmentVerifier(onEnroll = { EnrollmentResult.Approved(CREDENTIAL, WINDOW_24H) })
        val manager = newManager(store, verifier, device)

        manager.enroll(" ORG ", "SITE", " store-code ")

        val request = verifier.enrollRequests.single()
        assertEquals(EnrollmentRequest("ORG", "SITE", "device-1", "store-code"), request)
        assertEquals(DeviceAuthorizationState.Authorized(device.now() + 24 * HOUR), manager.state.value)
        assertTrue(manager.isAccessGranted())
        val saved = store.saved!!
        assertEquals(CREDENTIAL, saved.credential)
        assertNull(saved.denial)
        assertEquals(authorizationReceivedAt(device.now()), saved.offlineAuthorization)
    }

    @Test
    fun `rejected, recovery-required and failed enrollment never touch the store`() = runTest {
        val cases = listOf(
            EnrollmentResult.Rejected("bad site") to DeviceAuthorizationState.EnrollmentRejected("bad site"),
            EnrollmentResult.RecoveryRequired("already enrolled") to DeviceAuthorizationState.RecoveryRequired("already enrolled"),
            EnrollmentResult.Failure("offline") to DeviceAuthorizationState.EnrollmentFailed("offline")
        )
        for ((result, expected) in cases) {
            val store = FakeEnrollmentStore()
            val manager = newManager(store, FakeEnrollmentVerifier(onEnroll = { result }), TestDevice(testScheduler))

            manager.enroll("ORG", "SITE", "auth")

            assertEquals(expected, manager.state.value)
            assertNull(store.saved)
            assertFalse(manager.isAccessGranted())
        }
    }

    @Test
    fun `approval that cannot be saved locally becomes RecoveryRequired, not access`() = runTest {
        val store = FakeEnrollmentStore().apply { failNextSaveWith = IllegalStateException("disk full") }
        val verifier = FakeEnrollmentVerifier(onEnroll = { EnrollmentResult.Approved(CREDENTIAL, WINDOW_24H) })
        val manager = newManager(store, verifier, TestDevice(testScheduler))

        manager.enroll("ORG", "SITE", "auth")

        assertTrue(manager.state.value is DeviceAuthorizationState.RecoveryRequired)
        assertFalse(manager.isAccessGranted())
        assertNull(store.saved)
        assertEquals("never retried automatically", 1, verifier.enrollRequests.size)
    }

    @Test
    fun `enrollment is not offered while authorized or revoked`() = runTest {
        val device = TestDevice(testScheduler)
        val verifier = FakeEnrollmentVerifier(onEnroll = { EnrollmentResult.Approved(NEW_CREDENTIAL, WINDOW_24H) })
        val store = FakeEnrollmentStore(StoredEnrollment(CREDENTIAL, authorizationReceivedAt(device.now()), null))
        val manager = newManager(store, verifier, device)
        manager.enroll("ORG", "SITE", "auth")
        assertTrue(verifier.enrollRequests.isEmpty())
        assertEquals(CREDENTIAL, store.saved!!.credential)

        val revokedStore = FakeEnrollmentStore(StoredEnrollment(CREDENTIAL, null, DenialReason.REVOKED))
        val revoked = newManager(revokedStore, verifier, device)
        revoked.enroll("ORG", "SITE", "auth")
        assertTrue(verifier.enrollRequests.isEmpty())
        assertEquals(DeviceAuthorizationState.Revoked, revoked.state.value)
    }

    @Test
    fun `controlled re-enrollment from InvalidCredential replaces the credential only on approval`() = runTest {
        val store = FakeEnrollmentStore(StoredEnrollment(CREDENTIAL, null, DenialReason.INVALID_CREDENTIAL))
        val verifier = FakeEnrollmentVerifier(onEnroll = { EnrollmentResult.Rejected("authorization expired") })
        val manager = newManager(store, verifier, TestDevice(testScheduler))
        assertEquals(DeviceAuthorizationState.InvalidCredential, manager.state.value)

        manager.enroll("ORG", "SITE", "stale-auth")
        assertEquals(DeviceAuthorizationState.EnrollmentRejected("authorization expired"), manager.state.value)
        assertEquals("rejection keeps the old record", CREDENTIAL, store.saved!!.credential)

        verifier.onEnroll = { EnrollmentResult.Approved(NEW_CREDENTIAL, WINDOW_24H) }
        manager.enroll("ORG", "SITE", "fresh-auth")
        assertTrue(manager.state.value is DeviceAuthorizationState.Authorized)
        assertEquals(StoredEnrollment(NEW_CREDENTIAL, store.saved!!.offlineAuthorization, null), store.saved)
    }

    @Test
    fun `re-enrollment is also offered from RecoveryRequired and UnknownDevice`() = runTest {
        val unknownStore = FakeEnrollmentStore(StoredEnrollment(CREDENTIAL, null, DenialReason.UNKNOWN_DEVICE))
        val verifier = FakeEnrollmentVerifier(onEnroll = { EnrollmentResult.RecoveryRequired("already enrolled") })
        val manager = newManager(unknownStore, verifier, TestDevice(testScheduler))
        assertEquals(DeviceAuthorizationState.UnknownDevice, manager.state.value)

        manager.enroll("ORG", "SITE", "a1")
        assertEquals(DeviceAuthorizationState.RecoveryRequired("already enrolled"), manager.state.value)

        verifier.onEnroll = { EnrollmentResult.Approved(NEW_CREDENTIAL, WINDOW_24H) }
        manager.enroll("ORG", "SITE", "a2")
        assertTrue(manager.state.value is DeviceAuthorizationState.Authorized)
        assertEquals(2, verifier.enrollRequests.size)
    }

    // ------------------------------------------------------------------
    // Triggers
    // ------------------------------------------------------------------

    @Test
    fun `launch with a valid stored allowance is OfflineAuthorized, then the foreground trigger verifies`() = runTest {
        val device = TestDevice(testScheduler)
        val stored = authorizationReceivedAt(device.now() - HOUR)
        val store = FakeEnrollmentStore(StoredEnrollment(CREDENTIAL, stored, null))
        val verifier = FakeEnrollmentVerifier(onVerify = { VerificationResult.Authorized(WINDOW_24H) })
        val manager = newManager(store, verifier, device)

        assertEquals(
            DeviceAuthorizationState.OfflineAuthorized(stored.expiresAtElapsedRealtimeMs, lastVerificationProblem = null),
            manager.state.value
        )
        assertTrue(verifier.verifiedCredentials.isEmpty())

        val foreground = launch { manager.runWhileForeground(MutableStateFlow(true)) }
        runCurrent()

        assertEquals(listOf(CREDENTIAL), verifier.verifiedCredentials)
        assertTrue(manager.state.value is DeviceAuthorizationState.Authorized)
        foreground.cancel()
    }

    @Test
    fun `periodic verification runs every 15 minutes only while in the foreground`() = runTest {
        val device = TestDevice(testScheduler)
        val store = FakeEnrollmentStore(StoredEnrollment(CREDENTIAL, null, null))
        val verifier = FakeEnrollmentVerifier(onVerify = { VerificationResult.Authorized(WINDOW_24H) })
        val manager = newManager(store, verifier, device)

        val foreground = launch { manager.runWhileForeground(MutableStateFlow(true)) }
        runCurrent()
        assertEquals(1, verifier.verifiedCredentials.size)

        advanceTimeBy(15 * MINUTE - 1); runCurrent()
        assertEquals(1, verifier.verifiedCredentials.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, verifier.verifiedCredentials.size)
        advanceTimeBy(15 * MINUTE); runCurrent()
        assertEquals(3, verifier.verifiedCredentials.size)

        foreground.cancel() // ON_STOP
        advanceTimeBy(2 * HOUR); runCurrent()
        assertEquals("no checks while backgrounded", 3, verifier.verifiedCredentials.size)

        val again = launch { manager.runWhileForeground(MutableStateFlow(true)) } // ON_START
        runCurrent()
        assertEquals("return to foreground verifies immediately", 4, verifier.verifiedCredentials.size)
        again.cancel()
    }

    @Test
    fun `connectivity return triggers verification with bounded backoff`() = runTest {
        val device = TestDevice(testScheduler)
        val store = FakeEnrollmentStore(StoredEnrollment(CREDENTIAL, null, null))
        val verifier = FakeEnrollmentVerifier(onVerify = { VerificationResult.Unavailable("offline") })
        val manager = newManager(store, verifier, device)
        val connectivity = MutableStateFlow(true)

        val foreground = launch { manager.runWhileForeground(connectivity) }
        runCurrent()
        assertEquals("initial (launch) check only; current connectivity is not a 'return'", 1, verifier.verifiedCredentials.size)

        advanceTimeBy(MINUTE)
        connectivity.value = false; runCurrent()
        assertEquals(1, verifier.verifiedCredentials.size)
        connectivity.value = true; runCurrent()
        assertEquals(2, verifier.verifiedCredentials.size)

        advanceTimeBy(2_000); runCurrent()
        assertEquals(3, verifier.verifiedCredentials.size)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(4, verifier.verifiedCredentials.size)
        advanceTimeBy(15_000); runCurrent()
        assertEquals(5, verifier.verifiedCredentials.size)

        advanceTimeBy(10 * MINUTE); runCurrent()
        assertEquals("retries are bounded (1 + 3)", 5, verifier.verifiedCredentials.size)
        assertEquals(DeviceAuthorizationState.ConnectionRequired, manager.state.value)
        foreground.cancel()
    }

    @Test
    fun `connectivity retries stop as soon as verification succeeds`() = runTest {
        val device = TestDevice(testScheduler)
        val store = FakeEnrollmentStore(StoredEnrollment(CREDENTIAL, null, null))
        var calls = 0
        val verifier = FakeEnrollmentVerifier(onVerify = {
            calls++
            if (calls < 3) VerificationResult.Unavailable("offline") else VerificationResult.Authorized(WINDOW_24H)
        })
        val manager = newManager(store, verifier, device)
        val connectivity = MutableStateFlow(false)

        val foreground = launch { manager.runWhileForeground(connectivity) }
        runCurrent() // call 1 (launch)
        connectivity.value = true; runCurrent() // call 2
        advanceTimeBy(2_000); runCurrent() // call 3 -> Authorized
        advanceTimeBy(5 * MINUTE); runCurrent()

        assertEquals(3, verifier.verifiedCredentials.size)
        assertTrue(manager.state.value is DeviceAuthorizationState.Authorized)
        foreground.cancel()
    }

    // ------------------------------------------------------------------
    // Single-flight and stale results
    // ------------------------------------------------------------------

    @Test
    fun `concurrent triggers share one in-flight verification`() = runTest {
        val store = FakeEnrollmentStore(StoredEnrollment(CREDENTIAL, null, null))
        val response = CompletableDeferred<VerificationResult>()
        val verifier = FakeEnrollmentVerifier(onVerify = { response.await() })
        val manager = newManager(store, verifier, TestDevice(testScheduler))

        val results = mutableListOf<VerificationResult?>()
        launch { results += manager.verifyNow() }
        launch { results += manager.verifyNow() }
        manager.requestVerification()
        runCurrent()
        assertEquals("no overlapping requests", 1, verifier.verifiedCredentials.size)

        response.complete(VerificationResult.Authorized(WINDOW_24H))
        runCurrent()
        assertEquals(listOf(VerificationResult.Authorized(WINDOW_24H), VerificationResult.Authorized(WINDOW_24H)), results)
        assertEquals(1, verifier.verifiedCredentials.size)
    }

    @Test
    fun `stale verification result is ignored after the credential was replaced by re-enrollment`() = runTest {
        val store = FakeEnrollmentStore(StoredEnrollment(CREDENTIAL, null, DenialReason.INVALID_CREDENTIAL))
        val oldResponse = CompletableDeferred<VerificationResult>()
        val verifier = FakeEnrollmentVerifier(
            onEnroll = { EnrollmentResult.Approved(NEW_CREDENTIAL, WINDOW_24H) },
            onVerify = { oldResponse.await() }
        )
        val manager = newManager(store, verifier, TestDevice(testScheduler))
        launch { manager.verifyNow() }
        runCurrent()
        assertEquals(listOf(CREDENTIAL), verifier.verifiedCredentials)

        manager.enroll("ORG", "SITE", "fresh-auth")
        assertTrue(manager.state.value is DeviceAuthorizationState.Authorized)

        oldResponse.complete(VerificationResult.InvalidCredential) // answer about the OLD credential
        runCurrent()

        assertTrue(manager.state.value is DeviceAuthorizationState.Authorized)
        assertEquals(NEW_CREDENTIAL, store.saved!!.credential)
        assertNull(store.saved!!.denial)
    }

    @Test
    fun `stale success cannot restore access once a newer enrollment attempt superseded it`() = runTest {
        val store = FakeEnrollmentStore(StoredEnrollment(CREDENTIAL, null, DenialReason.INVALID_CREDENTIAL))
        val oldResponse = CompletableDeferred<VerificationResult>()
        val verifier = FakeEnrollmentVerifier(
            onEnroll = { EnrollmentResult.Rejected("authorization expired") },
            onVerify = { oldResponse.await() }
        )
        val manager = newManager(store, verifier, TestDevice(testScheduler))
        launch { manager.verifyNow() }
        runCurrent()

        manager.enroll("ORG", "SITE", "auth")
        assertEquals(DeviceAuthorizationState.EnrollmentRejected("authorization expired"), manager.state.value)

        oldResponse.complete(VerificationResult.Authorized(WINDOW_24H))
        runCurrent()

        assertEquals(DeviceAuthorizationState.EnrollmentRejected("authorization expired"), manager.state.value)
        assertFalse(manager.isAccessGranted())
        assertEquals(DenialReason.INVALID_CREDENTIAL, store.saved!!.denial)
    }

    // ------------------------------------------------------------------
    // Offline allowance: renewal, expiry, clocks
    // ------------------------------------------------------------------

    @Test
    fun `successful renewal extends access only by the server-provided window from receipt`() = runTest {
        val device = TestDevice(testScheduler)
        val store = FakeEnrollmentStore(StoredEnrollment(CREDENTIAL, null, null))
        val verifier = FakeEnrollmentVerifier()
        val manager = authorizedManager(store, verifier, device)
        val start = device.now()
        assertEquals(DeviceAuthorizationState.Authorized(start + 24 * HOUR), manager.state.value)

        advanceTimeBy(10 * HOUR); runCurrent()
        val shortWindow = ServerAuthorizationWindow(SERVER_NOW, SERVER_NOW + 12 * HOUR)
        verifier.onVerify = { VerificationResult.Authorized(shortWindow) }
        manager.verifyNow()

        assertEquals(DeviceAuthorizationState.Authorized(start + 22 * HOUR), manager.state.value)
        assertEquals(start + 10 * HOUR, store.saved!!.offlineAuthorization!!.receivedAtElapsedRealtimeMs)

        advanceTimeBy(12 * HOUR - 1); runCurrent()
        assertTrue(manager.isAccessGranted())
        advanceTimeBy(1); runCurrent()
        assertEquals(DeviceAuthorizationState.ConnectionRequired, manager.state.value)
    }

    @Test
    fun `failed checks never extend the allowance and access ends exactly at the deadline`() = runTest {
        val device = TestDevice(testScheduler)
        val store = FakeEnrollmentStore(StoredEnrollment(CREDENTIAL, null, null))
        val verifier = FakeEnrollmentVerifier()
        val manager = authorizedManager(store, verifier, device)
        val deadline = device.now() + 24 * HOUR
        verifier.onVerify = { VerificationResult.Unavailable("HTTP 503") }

        advanceTimeBy(10 * HOUR); runCurrent()
        manager.verifyNow()
        assertEquals(DeviceAuthorizationState.OfflineAuthorized(deadline, "HTTP 503"), manager.state.value)
        assertTrue(manager.isAccessGranted())

        advanceTimeBy(10 * HOUR); runCurrent()
        manager.verifyNow()
        assertEquals(DeviceAuthorizationState.OfflineAuthorized(deadline, "HTTP 503"), manager.state.value)

        // No check is running at the deadline: the scheduled transition alone must block.
        advanceTimeBy(4 * HOUR - 1); runCurrent()
        assertTrue(manager.isAccessGranted())
        advanceTimeBy(1); runCurrent()
        assertEquals(DeviceAuthorizationState.ConnectionRequired, manager.state.value)
        assertFalse(manager.isAccessGranted())
        assertEquals("credential is kept", CREDENTIAL, store.saved!!.credential)
    }

    @Test
    fun `successful verification after connection-required restores access without re-enrollment`() = runTest {
        val store = FakeEnrollmentStore(StoredEnrollment(CREDENTIAL, null, null))
        val verifier = FakeEnrollmentVerifier(onVerify = { VerificationResult.Unavailable("offline") })
        val manager = newManager(store, verifier, TestDevice(testScheduler))
        manager.verifyNow()
        assertEquals(DeviceAuthorizationState.ConnectionRequired, manager.state.value)

        verifier.onVerify = { VerificationResult.Authorized(WINDOW_24H) }
        manager.verifyNow()

        assertTrue(manager.state.value is DeviceAuthorizationState.Authorized)
        assertTrue(verifier.enrollRequests.isEmpty())
    }

    @Test
    fun `absolute server timestamps and device wall clock do not affect the allowance`() = runTest {
        // Two windows with wildly different absolute instants but the same duration yield
        // the same monotonic deadline; the manager has no wall-clock dependency at all.
        val device = TestDevice(testScheduler)
        val farPast = ServerAuthorizationWindow(0L, 24 * HOUR)
        val farFuture = ServerAuthorizationWindow(4_000_000_000_000L, 4_000_000_000_000L + 24 * HOUR)
        for (window in listOf(farPast, farFuture)) {
            val store = FakeEnrollmentStore(StoredEnrollment(CREDENTIAL, null, null))
            val manager = newManager(store, FakeEnrollmentVerifier(onVerify = { VerificationResult.Authorized(window) }), device)
            manager.verifyNow()
            assertEquals(DeviceAuthorizationState.Authorized(device.now() + 24 * HOUR), manager.state.value)
        }
    }

    @Test
    fun `deadline is enforced even if the timer is late (delay clock paused in deep sleep)`() = runTest {
        val device = TestDevice(testScheduler)
        val manager = authorizedManager(FakeEnrollmentStore(StoredEnrollment(CREDENTIAL, null, null)), FakeEnrollmentVerifier(), device)

        device.unscheduledElapsedMs = 25 * HOUR // elapsedRealtime advanced, scheduler did not

        assertFalse(manager.isAccessGranted())
        runCurrent()
        assertEquals(DeviceAuthorizationState.ConnectionRequired, manager.state.value)
    }

    @Test
    fun `process restart in the same boot keeps only the remaining allowance`() = runTest {
        val device = TestDevice(testScheduler)
        val store = FakeEnrollmentStore()
        val enrollVerifier = FakeEnrollmentVerifier(onEnroll = { EnrollmentResult.Approved(CREDENTIAL, WINDOW_24H) })
        val firstProcess = processScope()
        val first = newManager(store, enrollVerifier, device, scope = firstProcess)
        first.enroll("ORG", "SITE", "auth")
        val deadline = device.now() + 24 * HOUR
        firstProcess.cancel() // process death

        advanceTimeBy(5 * HOUR); runCurrent()
        val second = newManager(store, FakeEnrollmentVerifier(onVerify = { VerificationResult.Unavailable("offline") }), device)

        assertEquals(DeviceAuthorizationState.OfflineAuthorized(deadline, null), second.state.value)
        assertTrue(second.isAccessGranted())
        advanceTimeBy(19 * HOUR - 1); runCurrent()
        assertTrue(second.isAccessGranted())
        advanceTimeBy(1); runCurrent()
        assertEquals(DeviceAuthorizationState.ConnectionRequired, second.state.value)
    }

    @Test
    fun `reboot or unreadable boot identity voids the stored allowance`() = runTest {
        val device = TestDevice(testScheduler)
        val stored = StoredEnrollment(CREDENTIAL, authorizationReceivedAt(device.now()), null)

        device.bootCount = BOOT_COUNT + 1
        device.elapsedBase = 10_000L // elapsedRealtime restarts after a reboot
        val afterReboot = newManager(FakeEnrollmentStore(stored), FakeEnrollmentVerifier(), device)
        assertEquals(DeviceAuthorizationState.Checking, afterReboot.state.value)
        assertFalse(afterReboot.isAccessGranted())

        // Same boot count but the clock is before the anchor: also void.
        device.bootCount = BOOT_COUNT
        val clockBehind = newManager(FakeEnrollmentStore(stored), FakeEnrollmentVerifier(), device)
        assertEquals(DeviceAuthorizationState.Checking, clockBehind.state.value)

        device.elapsedBase = BOOT_ELAPSED_AT_START
        device.bootCount = null
        val unreadable = newManager(FakeEnrollmentStore(stored), FakeEnrollmentVerifier(), device)
        assertEquals(DeviceAuthorizationState.Checking, unreadable.state.value)

        // An allowance received while the boot count was unreadable never survives a reload.
        device.bootCount = BOOT_COUNT
        val untagged = StoredEnrollment(CREDENTIAL, authorizationReceivedAt(device.now(), boot = null), null)
        val reloadedUntagged = newManager(FakeEnrollmentStore(untagged), FakeEnrollmentVerifier(), device)
        assertEquals(DeviceAuthorizationState.Checking, reloadedUntagged.state.value)
    }

    @Test
    fun `boot count unreadable at receipt still grants in-process access until the deadline`() = runTest {
        val device = TestDevice(testScheduler).apply { bootCount = null }
        val store = FakeEnrollmentStore(StoredEnrollment(CREDENTIAL, null, null))
        val manager = authorizedManager(store, FakeEnrollmentVerifier(), device)

        assertTrue(manager.isAccessGranted())
        assertNull(store.saved!!.offlineAuthorization!!.bootCount)
        advanceTimeBy(24 * HOUR); runCurrent()
        assertEquals(DeviceAuthorizationState.ConnectionRequired, manager.state.value)
    }

    // ------------------------------------------------------------------
    // Explicit denials
    // ------------------------------------------------------------------

    @Test
    fun `explicit denial blocks immediately despite a valid allowance and is persisted`() = runTest {
        val cases = listOf(
            VerificationResult.Revoked to (DeviceAuthorizationState.Revoked to DenialReason.REVOKED),
            VerificationResult.InvalidCredential to (DeviceAuthorizationState.InvalidCredential to DenialReason.INVALID_CREDENTIAL),
            VerificationResult.UnknownDevice to (DeviceAuthorizationState.UnknownDevice to DenialReason.UNKNOWN_DEVICE)
        )
        for ((result, expected) in cases) {
            val device = TestDevice(testScheduler)
            val store = FakeEnrollmentStore(StoredEnrollment(CREDENTIAL, authorizationReceivedAt(device.now()), null))
            val manager = newManager(store, FakeEnrollmentVerifier(onVerify = { result }), device)
            assertTrue(manager.isAccessGranted())

            manager.verifyNow()

            assertEquals(expected.first, manager.state.value)
            assertFalse(manager.isAccessGranted())
            assertEquals(StoredEnrollment(CREDENTIAL, offlineAuthorization = null, denial = expected.second), store.saved)
        }
    }

    @Test
    fun `persisted denial survives a restart while offline`() = runTest {
        val device = TestDevice(testScheduler)
        val store = FakeEnrollmentStore(StoredEnrollment(CREDENTIAL, authorizationReceivedAt(device.now()), null))
        val firstProcess = processScope()
        val first = newManager(store, FakeEnrollmentVerifier(onVerify = { VerificationResult.Revoked }), device, scope = firstProcess)
        first.verifyNow()
        firstProcess.cancel()

        advanceTimeBy(MINUTE); runCurrent()
        val offlineVerifier = FakeEnrollmentVerifier(onVerify = { VerificationResult.Unavailable("offline") })
        val second = newManager(store, offlineVerifier, device)
        assertEquals(DeviceAuthorizationState.Revoked, second.state.value)
        assertFalse(second.isAccessGranted())

        second.verifyNow()
        assertEquals("unavailability never lifts a denial", DeviceAuthorizationState.Revoked, second.state.value)
    }

    @Test
    fun `revoked device keeps checking so an administrator reset is noticed`() = runTest {
        val store = FakeEnrollmentStore(StoredEnrollment(CREDENTIAL, null, DenialReason.REVOKED))
        val verifier = FakeEnrollmentVerifier(onVerify = { VerificationResult.InvalidCredential })
        val manager = newManager(store, verifier, TestDevice(testScheduler))

        manager.verifyNow()

        assertEquals(DeviceAuthorizationState.InvalidCredential, manager.state.value)
    }

    @Test
    fun `verification is paused while an enrollment form state owns the screen`() = runTest {
        val store = FakeEnrollmentStore(StoredEnrollment(CREDENTIAL, null, DenialReason.INVALID_CREDENTIAL))
        val verifier = FakeEnrollmentVerifier(onEnroll = { EnrollmentResult.Failure("offline") })
        val manager = newManager(store, verifier, TestDevice(testScheduler))
        manager.enroll("ORG", "SITE", "auth")

        assertNull(manager.verifyNow())
        assertTrue(verifier.verifiedCredentials.isEmpty())
    }

    @Test
    fun `a verifier that throws is treated as unavailable, never as a denial`() = runTest {
        val device = TestDevice(testScheduler)
        val store = FakeEnrollmentStore(StoredEnrollment(CREDENTIAL, authorizationReceivedAt(device.now()), null))
        val manager = newManager(store, FakeEnrollmentVerifier(onVerify = { throw IllegalStateException("bug") }), device)

        manager.verifyNow()

        assertTrue(manager.state.value is DeviceAuthorizationState.OfflineAuthorized)
        assertTrue(manager.isAccessGranted())
        assertNull(store.saved!!.denial)
    }

    // ------------------------------------------------------------------
    // Review follow-ups: unreadable storage, denial persistence, foreground re-check
    // ------------------------------------------------------------------

    @Test
    fun `unreadable stored enrollment is RecoveryRequired, never NotEnrolled`() = runTest {
        val store = FakeEnrollmentStore().apply {
            failLoadWith = EnrollmentDataUnreadableException("Stored enrollment data was corrupted.")
        }
        val verifier = FakeEnrollmentVerifier(onEnroll = { EnrollmentResult.Approved(NEW_CREDENTIAL, WINDOW_24H) })
        val manager = newManager(store, verifier, TestDevice(testScheduler))

        val state = manager.state.value
        assertTrue("was $state", state is DeviceAuthorizationState.RecoveryRequired)
        assertTrue((state as DeviceAuthorizationState.RecoveryRequired).reason.startsWith("Enrollment data on this device could not be read"))
        assertFalse(manager.isAccessGranted())
        assertNull("nothing to verify", manager.verifyNow())

        // Recovery path: after an administrator reset, re-enrollment overwrites the bad data.
        store.failLoadWith = null
        manager.enroll("ORG", "SITE", "fresh-auth")
        assertTrue(manager.state.value is DeviceAuthorizationState.Authorized)
        assertEquals(NEW_CREDENTIAL, store.saved!!.credential)
    }

    @Test
    fun `secure storage that cannot be opened gets its own honest remedy, on load and on save`() = runTest {
        val expected = DeviceAuthorizationState.RecoveryRequired(
            "Secure storage on this device is unavailable. Clear Orion's app data or reinstall, " +
                "then ask an administrator to reset this device."
        )
        val brokenOnLoad = FakeEnrollmentStore().apply {
            failLoadWith = SecureStorageUnavailableException("Secure storage could not be opened.")
        }
        assertEquals(expected, newManager(brokenOnLoad, FakeEnrollmentVerifier(), TestDevice(testScheduler)).state.value)

        val brokenOnSave = FakeEnrollmentStore().apply {
            failNextSaveWith = SecureStorageUnavailableException("Secure storage could not be used.")
        }
        val verifier = FakeEnrollmentVerifier(onEnroll = { EnrollmentResult.Approved(CREDENTIAL, WINDOW_24H) })
        val manager = newManager(brokenOnSave, verifier, TestDevice(testScheduler))
        manager.enroll("ORG", "SITE", "auth")
        assertEquals(expected, manager.state.value)
        assertFalse(manager.isAccessGranted())
        assertNull("nothing deleted or faked", brokenOnSave.saved)

        // Ordinary corruption keeps the "ask an administrator" remedy.
        val corrupt = FakeEnrollmentStore().apply { failLoadWith = EnrollmentDataUnreadableException("corrupted") }
        val corruptState = newManager(corrupt, FakeEnrollmentVerifier(), TestDevice(testScheduler)).state.value
        assertTrue(corruptState is DeviceAuthorizationState.RecoveryRequired && corruptState != expected)
    }

    @Test
    fun `a denial whose save failed is persisted by the next trigger and then survives restart`() = runTest {
        val device = TestDevice(testScheduler)
        val store = FakeEnrollmentStore(StoredEnrollment(CREDENTIAL, authorizationReceivedAt(device.now()), null))
        val verifier = FakeEnrollmentVerifier(onVerify = { VerificationResult.Revoked })
        val firstProcess = processScope()
        val first = newManager(store, verifier, device, scope = firstProcess)

        store.failNextSaveWith = IllegalStateException("disk full")
        first.verifyNow()
        assertEquals(DeviceAuthorizationState.Revoked, first.state.value)
        assertFalse(first.isAccessGranted())
        assertTrue("save failed, old allowance still on disk", store.saved!!.offlineAuthorization != null)

        // Any later trigger retries the write before doing anything else, even offline.
        verifier.onVerify = { VerificationResult.Unavailable("offline") }
        first.verifyNow()
        assertEquals(StoredEnrollment(CREDENTIAL, null, DenialReason.REVOKED), store.saved)
        firstProcess.cancel()

        val second = newManager(store, FakeEnrollmentVerifier(onVerify = { VerificationResult.Unavailable("offline") }), device)
        assertEquals(DeviceAuthorizationState.Revoked, second.state.value)
        assertFalse(second.isAccessGranted())
    }

    @Test
    fun `failed denial save is also retried on foreground start without a network result`() = runTest {
        val device = TestDevice(testScheduler)
        val store = FakeEnrollmentStore(StoredEnrollment(CREDENTIAL, authorizationReceivedAt(device.now()), null))
        val verifier = FakeEnrollmentVerifier(onVerify = { VerificationResult.UnknownDevice })
        val manager = newManager(store, verifier, device)
        store.failNextSaveWith = IllegalStateException("disk full")
        manager.verifyNow()
        assertTrue(store.saved!!.offlineAuthorization != null)

        // From now on the network never answers, so only the foreground-start retry can write.
        val neverAnswers = CompletableDeferred<VerificationResult>()
        verifier.onVerify = { neverAnswers.await() }
        val foreground = launch { manager.runWhileForeground(MutableStateFlow(true)) }
        runCurrent()

        assertEquals(StoredEnrollment(CREDENTIAL, null, DenialReason.UNKNOWN_DEVICE), store.saved)
        foreground.cancel()
    }

    @Test
    fun `foreground start re-checks the deadline even when no verification answers`() = runTest {
        val device = TestDevice(testScheduler)
        val store = FakeEnrollmentStore(StoredEnrollment(CREDENTIAL, authorizationReceivedAt(device.now()), null))
        val neverAnswers = CompletableDeferred<VerificationResult>()
        val manager = newManager(store, FakeEnrollmentVerifier(onVerify = { neverAnswers.await() }), device)
        assertTrue(manager.state.value is DeviceAuthorizationState.OfflineAuthorized)

        device.unscheduledElapsedMs = 25 * HOUR // deep sleep: timer paused, elapsedRealtime advanced
        val foreground = launch { manager.runWhileForeground(MutableStateFlow(true)) }
        runCurrent()

        assertEquals(DeviceAuthorizationState.ConnectionRequired, manager.state.value)
        foreground.cancel()
    }
}
