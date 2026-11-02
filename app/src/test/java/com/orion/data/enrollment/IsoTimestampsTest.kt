package com.orion.data.enrollment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IsoTimestampsTest {

    @Test
    fun `parses UTC Z timestamps`() {
        assertEquals(0L, parseIsoInstantToEpochMillis("1970-01-01T00:00:00Z"))
        // 2026-11-02T12:00:00Z, cross-checked with java.time on the JVM.
        assertEquals(java.time.Instant.parse("2026-11-02T12:00:00Z").toEpochMilli(), parseIsoInstantToEpochMillis("2026-11-02T12:00:00Z"))
        assertEquals(java.time.Instant.parse("2024-02-29T23:59:59Z").toEpochMilli(), parseIsoInstantToEpochMillis("2024-02-29T23:59:59Z"))
    }

    @Test
    fun `applies explicit offsets and truncates fractional seconds to millis`() {
        assertEquals(
            java.time.OffsetDateTime.parse("2026-11-02T14:00:00.123456+02:00").toInstant().toEpochMilli(),
            parseIsoInstantToEpochMillis("2026-11-02T14:00:00.123456+02:00")
        )
        assertEquals(
            java.time.Instant.parse("2026-11-02T18:00:51.362197Z").toEpochMilli(),
            parseIsoInstantToEpochMillis("2026-11-02T18:00:51.362197Z")
        )
        assertEquals(
            java.time.Instant.parse("2026-11-02T12:00:00.500Z").toEpochMilli(),
            parseIsoInstantToEpochMillis("2026-11-02T12:00:00.5Z")
        )
        assertEquals(
            parseIsoInstantToEpochMillis("2026-11-02T12:00:00Z"),
            parseIsoInstantToEpochMillis("2026-11-02T07:00:00-05:00")
        )
    }

    @Test
    fun `rejects zone-less, malformed, and impossible timestamps`() {
        for (bad in listOf(
            "2026-11-02T12:00:00", "2026-11-02 12:00:00Z", "not a date", "",
            "2026-02-30T00:00:00Z", "2025-02-29T00:00:00Z", "2026-13-01T00:00:00Z",
            "2026-11-02T24:00:00Z", "2026-11-02T12:00:00+25:00"
        )) {
            assertNull(bad, parseIsoInstantToEpochMillis(bad))
        }
    }
}
