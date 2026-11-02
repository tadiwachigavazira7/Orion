// ============================================================
// data/enrollment/IsoTimestamps.kt
// Minimal ISO-8601 timestamp -> epoch-millis parser for the enrollment
// backend's `server_time` / `offline_valid_until` fields.
//
// Why hand-rolled: java.time (Instant/OffsetDateTime) is API 26+, minSdk is
// 24, and core-library desugaring is not configured; SimpleDateFormat cannot
// parse variable-length fractional seconds (e.g. Python's microseconds)
// correctly. This accepts exactly the contract's shape: an explicit UTC `Z`
// or a `+HH:MM`/`-HH:MM` offset is REQUIRED (a zone-less timestamp is
// rejected rather than guessed).
// ============================================================
package com.orion.data.enrollment

private val ISO_INSTANT = Regex(
    """^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2})$"""
)

/** Parses an ISO-8601 timestamp with an explicit offset to epoch millis, or `null` if malformed. */
internal fun parseIsoInstantToEpochMillis(value: String): Long? {
    val match = ISO_INSTANT.matchEntire(value) ?: return null
    val g = match.groupValues
    val year = g[1].toInt()
    val month = g[2].toInt()
    val day = g[3].toInt()
    val hour = g[4].toInt()
    val minute = g[5].toInt()
    val second = g[6].toInt()
    val millis = g[7].takeIf { it.isNotEmpty() }?.padEnd(3, '0')?.take(3)?.toInt() ?: 0

    if (month !in 1..12 || day < 1 || day > daysInMonth(year, month)) return null
    if (hour > 23 || minute > 59 || second > 59) return null

    val offsetMinutes = when (val zone = g[8]) {
        "Z" -> 0
        else -> {
            val sign = if (zone[0] == '-') -1 else 1
            val offsetHours = zone.substring(1, 3).toInt()
            val offsetMins = zone.substring(4, 6).toInt()
            if (offsetHours > 18 || offsetMins > 59) return null
            sign * (offsetHours * 60 + offsetMins)
        }
    }

    val days = daysFromCivil(year, month, day)
    val secondsOfDay = hour * 3600L + minute * 60L + second
    return (days * 86_400L + secondsOfDay - offsetMinutes * 60L) * 1000L + millis
}

private fun isLeapYear(year: Int) = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

private fun daysInMonth(year: Int, month: Int) = when (month) {
    2 -> if (isLeapYear(year)) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}

/** Days since 1970-01-01 for a proleptic Gregorian date (H. Hinnant's days_from_civil). */
private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = (if (month <= 2) year - 1 else year).toLong()
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val mp = (month + 9) % 12
    val doy = (153 * mp + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097 + doe - 719_468
}
