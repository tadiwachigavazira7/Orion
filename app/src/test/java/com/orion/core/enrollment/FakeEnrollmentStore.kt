package com.orion.core.enrollment

/**
 * In-memory test-only store. Shared between two manager instances to simulate a
 * process restart (the "disk" survives, the in-memory manager does not).
 */
class FakeEnrollmentStore(initial: StoredEnrollment? = null) : EnrollmentStore {
    var saved: StoredEnrollment? = initial
        private set
    var saveCount = 0
        private set

    /** When non-null, the next save() throws this instead of persisting. */
    var failNextSaveWith: Exception? = null

    /** When non-null, load() throws this (e.g. EnrollmentDataUnreadableException). */
    var failLoadWith: Exception? = null

    override suspend fun load(): StoredEnrollment? {
        failLoadWith?.let { throw it }
        return saved
    }

    override suspend fun save(record: StoredEnrollment) {
        failNextSaveWith?.let {
            failNextSaveWith = null
            throw it
        }
        saveCount++
        saved = record
    }
}
