// ============================================================
// app/AngleMath.kt
// Generic angle helpers for driving Compose animations (sweep/pulse angle,
// gauge needle rotation). NOT a bearing/direction estimate — CLAUDE.md §10
// forbids fabricating a compass bearing the RSSI signal can't support.
// Pure Kotlin, no Android/Compose imports, unit-testable on the JVM.
// ============================================================
package com.orion.app

/** Wraps any angle (negative, >360, exact multiples) into `[0.0, 360.0)`. */
fun normalizeDegrees(angle: Double): Double {
    val wrapped = angle % 360.0
    return if (wrapped < 0.0) wrapped + 360.0 else wrapped
}

/**
 * Signed shortest rotation from [from] to [to] in degrees, range `(-180.0, 180.0]`.
 * Use this to animate a needle/sweep between two angles without it visibly
 * spinning the long way around the 0°/360° seam.
 */
fun shortestAngleDelta(from: Double, to: Double): Double {
    var delta = (to - from) % 360.0
    if (delta <= -180.0) delta += 360.0
    if (delta > 180.0) delta -= 360.0
    return delta
}
