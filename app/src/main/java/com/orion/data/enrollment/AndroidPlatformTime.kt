// ============================================================
// data/enrollment/AndroidPlatformTime.kt
// Android implementations of the core.enrollment time/boot seams.
// ============================================================
package com.orion.data.enrollment

import android.content.ContentResolver
import android.os.SystemClock
import android.provider.Settings
import com.orion.core.enrollment.BootIdentityProvider
import com.orion.core.enrollment.MonotonicClock

/**
 * SystemClock.elapsedRealtime(): milliseconds since boot, including deep sleep,
 * and not affected by wall-clock (user/network) time changes. Resets on reboot,
 * which is why every anchor is paired with [SettingsBootIdentityProvider].
 */
class SystemClockMonotonicClock : MonotonicClock {
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
}

/**
 * Settings.Global.BOOT_COUNT ("boot_count", available since API 24 -- this app's
 * minSdk), the number of boots since the device was first set up. Returns `null`
 * when the setting is absent/unreadable so the caller voids any stored allowance
 * instead of guessing.
 */
class SettingsBootIdentityProvider(private val contentResolver: ContentResolver) : BootIdentityProvider {
    override fun currentBootCount(): Int? =
        try {
            Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT)
        } catch (e: Settings.SettingNotFoundException) {
            null // documented "unknown boot identity" outcome; see KDoc
        }
}
