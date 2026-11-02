package com.orion.app

import androidx.lifecycle.ViewModelStore
import com.orion.core.enrollment.*
import com.orion.data.enrollment.DEVICE_LIMIT_REACHED_MESSAGE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

private const val DEVICE_ID = "test-device-id"
private val VALID_CREDENTIAL = EnrollmentCredential("org-1", "site-1", DEVICE_ID, "opaque-token")
private val WINDOW = ServerAuthorizationWindow(1_000L, 86_401_000L)

@OptIn(ExperimentalCoroutinesApi::class)
class EnrollmentViewModelTest {
    private val viewModels = mutableListOf<EnrollmentViewModel>()
    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() {
        viewModels.forEach { model -> ViewModelStore().apply { put("test", model); clear() } }
        Dispatchers.resetMain()
    }

    private fun newViewModel(
        store: FakeEnrollmentStore = FakeEnrollmentStore(),
        result: EnrollmentResult = EnrollmentResult.Approved(VALID_CREDENTIAL, WINDOW),
        deviceId: String? = DEVICE_ID
    ): EnrollmentViewModel = EnrollmentViewModel(
        store = store,
        verifier = FakeEnrollmentVerifier(onEnroll = { result }),
        deviceIdProvider = FakeDeviceIdProvider(deviceId),
        clock = MonotonicClock { 100L },
        bootIdentity = BootIdentityProvider { 1 }
    ).also { viewModels.add(it) }

    @Test fun `nothing stored requires enrollment`() {
        val model = newViewModel()
        assertEquals(DeviceAuthorizationState.NotEnrolled, model.state.value)
        assertEquals(DEVICE_ID, model.deviceId)
        assertFalse(model.accessGate.isAccessGranted())
    }

    @Test fun `legacy credential requires verification rather than granting access`() {
        val model = newViewModel(FakeEnrollmentStore(StoredEnrollment(VALID_CREDENTIAL, null, null)))
        assertEquals(DeviceAuthorizationState.Checking, model.state.value)
        assertFalse(model.accessGate.isAccessGranted())
    }

    @Test fun `valid same boot allowance grants offline access`() {
        val allowance = OfflineAuthorization.anchor(WINDOW, 0L, 1)
        val model = newViewModel(FakeEnrollmentStore(StoredEnrollment(VALID_CREDENTIAL, allowance, null)))
        assertTrue(model.state.value is DeviceAuthorizationState.OfflineAuthorized)
        assertTrue(model.accessGate.isAccessGranted())
    }

    @Test fun `another boot cannot restore offline access`() {
        val allowance = OfflineAuthorization.anchor(WINDOW, 0L, 2)
        val model = newViewModel(FakeEnrollmentStore(StoredEnrollment(VALID_CREDENTIAL, allowance, null)))
        assertEquals(DeviceAuthorizationState.Checking, model.state.value)
        assertFalse(model.accessGate.isAccessGranted())
    }

    @Test fun `approved enrollment saves credential and allowance and grants access`() {
        val store = FakeEnrollmentStore()
        val model = newViewModel(store)
        model.enroll("ORG", "SITE", "STORE-ENROLLMENT-CODE")
        assertTrue(model.state.value is DeviceAuthorizationState.Authorized)
        assertEquals(VALID_CREDENTIAL, store.saved?.credential)
        assertNotNull(store.saved?.offlineAuthorization)
        assertTrue(model.accessGate.isAccessGranted())
    }

    @Test fun `rejected enrollment does not save or grant access`() {
        val store = FakeEnrollmentStore()
        val model = newViewModel(store, EnrollmentResult.Rejected("unknown organization"))
        model.enroll("BAD", "SITE", "STORE-ENROLLMENT-CODE")
        assertEquals(DeviceAuthorizationState.EnrollmentRejected("unknown organization"), model.state.value)
        assertNull(store.saved)
        assertFalse(model.accessGate.isAccessGranted())
    }

    @Test fun `store capacity rejection shows the capacity message and the user can retry`() {
        val store = FakeEnrollmentStore()
        val verifier = FakeEnrollmentVerifier(onEnroll = { EnrollmentResult.Rejected(DEVICE_LIMIT_REACHED_MESSAGE) })
        val model = EnrollmentViewModel(
            store = store,
            verifier = verifier,
            deviceIdProvider = FakeDeviceIdProvider(DEVICE_ID),
            clock = MonotonicClock { 100L },
            bootIdentity = BootIdentityProvider { 1 }
        ).also { viewModels.add(it) }

        model.enroll("ORG", "SITE", "STORE-ENROLLMENT-CODE")

        val rejected = model.state.value
        assertEquals(DeviceAuthorizationState.EnrollmentRejected(DEVICE_LIMIT_REACHED_MESSAGE), rejected)
        assertNull(store.saved)
        assertFalse(model.accessGate.isAccessGranted())
        assertTrue("form stays submittable", canSubmitEnrollment(rejected, "ORG", "SITE", "STORE-ENROLLMENT-CODE"))

        // After the administrator frees or adds a slot, the same code works.
        verifier.onEnroll = { EnrollmentResult.Approved(VALID_CREDENTIAL, WINDOW) }
        model.enroll("ORG", "SITE", "STORE-ENROLLMENT-CODE")
        assertTrue(model.state.value is DeviceAuthorizationState.Authorized)
        assertEquals(VALID_CREDENTIAL, store.saved?.credential)
        assertEquals(2, verifier.enrollRequests.size)
    }

    @Test fun `failed enrollment remains blocked`() {
        val model = newViewModel(result = EnrollmentResult.Failure("not configured"))
        model.enroll("ORG", "SITE", "STORE-ENROLLMENT-CODE")
        assertEquals(DeviceAuthorizationState.EnrollmentFailed("not configured"), model.state.value)
        assertFalse(model.accessGate.isAccessGranted())
    }

    @Test fun `persisted revocation overrides a valid allowance`() {
        val allowance = OfflineAuthorization.anchor(WINDOW, 0L, 1)
        val model = newViewModel(FakeEnrollmentStore(StoredEnrollment(VALID_CREDENTIAL, allowance, DenialReason.REVOKED)))
        assertEquals(DeviceAuthorizationState.Revoked, model.state.value)
        assertFalse(model.accessGate.isAccessGranted())
    }

    @Test fun `missing and blank device ids block enrollment`() {
        listOf(null, "", "   ").forEach { id ->
            val store = FakeEnrollmentStore()
            val model = newViewModel(store = store, deviceId = id)
            model.enroll("ORG", "SITE", "STORE-ENROLLMENT-CODE")
            assertEquals(DeviceAuthorizationState.DeviceIdUnavailable, model.state.value)
            assertNull(store.saved)
            assertFalse(model.accessGate.isAccessGranted())
        }
    }
}
