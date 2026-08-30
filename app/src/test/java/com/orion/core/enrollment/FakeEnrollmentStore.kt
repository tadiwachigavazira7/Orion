package com.orion.core.enrollment

/** In-memory test-only fake — never used in main source (see UnconfiguredEnrollmentVerifier for why). */
class FakeEnrollmentStore(initial: EnrollmentCredential? = null) : EnrollmentStore {
    var saved: EnrollmentCredential? = initial
        private set

    override suspend fun save(credential: EnrollmentCredential) {
        saved = credential
    }

    override suspend fun load(): EnrollmentCredential? = saved

    override suspend fun clear() {
        saved = null
    }
}
