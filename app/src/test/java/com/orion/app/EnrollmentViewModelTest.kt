package com.orion.app

import com.orion.core.enrollment.CheckEnrollmentUseCase
import com.orion.core.enrollment.EnrollDeviceUseCase
import com.orion.core.enrollment.EnrollmentCredential
import com.orion.core.enrollment.EnrollmentResult
import com.orion.core.enrollment.FakeDeviceIdProvider
import com.orion.core.enrollment.FakeEnrollmentStore
import com.orion.core.enrollment.FakeEnrollmentVerifier
import com.orion.core.enrollment.UnenrollDeviceUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val DEVICE_ID = "test-device-id"

private val VALID_CREDENTIAL = EnrollmentCredential(
    organizationId = "org-1",
    siteId = "site-1",
    deviceId = DEVICE_ID,
    token = "opaque-token",
    issuedAt = 0L,
    expiresAt = null
)

private val EXPIRED_CREDENTIAL = VALID_CREDENTIAL.copy(expiresAt = 1L)

@OptIn(ExperimentalCoroutinesApi::class)
class EnrollmentViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(
        store: FakeEnrollmentStore = FakeEnrollmentStore(),
        verifierResult: EnrollmentResult = EnrollmentResult.Approved(VALID_CREDENTIAL)
    ): Pair<EnrollmentViewModel, FakeEnrollmentStore> {
        val checkEnrollment = CheckEnrollmentUseCase(store, now = { 100L })
        val enrollDevice = EnrollDeviceUseCase(FakeEnrollmentVerifier(verifierResult), store)
        val unenrollDevice = UnenrollDeviceUseCase(store)
        val viewModel = EnrollmentViewModel(
            checkEnrollment = checkEnrollment,
            enrollDevice = enrollDevice,
            unenrollDevice = unenrollDevice,
            deviceIdProvider = FakeDeviceIdProvider(DEVICE_ID)
        )
        return viewModel to store
    }

    @Test
    fun `initial check with nothing stored yields NeedsEnrollment`() {
        val (viewModel, _) = newViewModel(store = FakeEnrollmentStore(null))

        val state = viewModel.state.value
        assertTrue(state is EnrollmentUiState.NeedsEnrollment)
        assertEquals(DEVICE_ID, (state as EnrollmentUiState.NeedsEnrollment).deviceId)
    }

    @Test
    fun `initial check with a valid credential yields Enrolled`() {
        val (viewModel, _) = newViewModel(store = FakeEnrollmentStore(VALID_CREDENTIAL))

        assertTrue(viewModel.state.value is EnrollmentUiState.Enrolled)
    }

    @Test
    fun `initial check with an expired credential yields NeedsReEnrollment`() {
        val (viewModel, _) = newViewModel(store = FakeEnrollmentStore(EXPIRED_CREDENTIAL))

        val state = viewModel.state.value
        assertTrue(state is EnrollmentUiState.NeedsReEnrollment)
        assertEquals(DEVICE_ID, (state as EnrollmentUiState.NeedsReEnrollment).deviceId)
    }

    @Test
    fun `enroll with an approving verifier drives Enrolling then Enrolled`() {
        val (viewModel, store) = newViewModel(
            store = FakeEnrollmentStore(null),
            verifierResult = EnrollmentResult.Approved(VALID_CREDENTIAL)
        )

        viewModel.enroll("ORG", "SITE")

        assertTrue(viewModel.state.value is EnrollmentUiState.Enrolled)
        assertEquals(VALID_CREDENTIAL, store.saved)
    }

    @Test
    fun `enroll with a rejecting verifier yields EnrollmentRejected`() {
        val (viewModel, store) = newViewModel(
            store = FakeEnrollmentStore(null),
            verifierResult = EnrollmentResult.Rejected("unknown organization")
        )

        viewModel.enroll("BAD", "SITE")

        val state = viewModel.state.value
        assertTrue(state is EnrollmentUiState.EnrollmentRejected)
        assertEquals("unknown organization", (state as EnrollmentUiState.EnrollmentRejected).reason)
        assertEquals(null, store.saved)
    }

    @Test
    fun `enroll with a failing verifier yields EnrollmentFailed`() {
        val (viewModel, _) = newViewModel(
            store = FakeEnrollmentStore(null),
            verifierResult = EnrollmentResult.Failure("not configured")
        )

        viewModel.enroll("ORG", "SITE")

        val state = viewModel.state.value
        assertTrue(state is EnrollmentUiState.EnrollmentFailed)
        assertEquals("not configured", (state as EnrollmentUiState.EnrollmentFailed).reason)
    }

    @Test
    fun `unenroll returns to NeedsEnrollment`() {
        val (viewModel, store) = newViewModel(store = FakeEnrollmentStore(VALID_CREDENTIAL))
        assertTrue(viewModel.state.value is EnrollmentUiState.Enrolled)

        viewModel.unenroll()

        assertTrue(viewModel.state.value is EnrollmentUiState.NeedsEnrollment)
        assertEquals(null, store.saved)
    }
}
