package com.orion.core.enrollment

/**
 * Scriptable test-only verifier. No verifier that can return Approved/Authorized
 * may live in main source -- this stays in test sources only.
 *
 * [onVerify]/[onEnroll] are suspending, so a test can hold a request open (e.g.
 * with a CompletableDeferred) to exercise single-flight and stale-result rules.
 */
class FakeEnrollmentVerifier(
    var onEnroll: suspend (EnrollmentRequest) -> EnrollmentResult = {
        EnrollmentResult.Failure("no enroll result scripted")
    },
    var onVerify: suspend (EnrollmentCredential) -> VerificationResult = {
        VerificationResult.Unavailable("no verify result scripted")
    }
) : EnrollmentVerifier {
    val enrollRequests = mutableListOf<EnrollmentRequest>()
    val verifiedCredentials = mutableListOf<EnrollmentCredential>()

    override suspend fun enroll(request: EnrollmentRequest): EnrollmentResult {
        enrollRequests += request
        return onEnroll(request)
    }

    override suspend fun verify(credential: EnrollmentCredential): VerificationResult {
        verifiedCredentials += credential
        return onVerify(credential)
    }
}
