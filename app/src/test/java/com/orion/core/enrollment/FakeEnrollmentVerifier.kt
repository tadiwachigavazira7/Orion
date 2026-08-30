package com.orion.core.enrollment

/**
 * Small scriptable test-only fake. Per the product requirement, no fake/hardcoded
 * verifier that can ever return Approved may live in main source — this stays in
 * test sources only.
 */
class FakeEnrollmentVerifier(private val result: EnrollmentResult) : EnrollmentVerifier {
    var lastRequest: EnrollmentRequest? = null
        private set

    override suspend fun verify(request: EnrollmentRequest): EnrollmentResult {
        lastRequest = request
        return result
    }
}
