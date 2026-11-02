// ============================================================
// data/enrollment/AndroidDeviceIdProvider.kt
// Standard, vendor-neutral Android API for a device identifier -- not tied
// to Zebra/Impinj/any RFID vendor SDK. See core.enrollment.DeviceIdProvider
// for why the abstraction lives in the domain package.
// ============================================================
package com.orion.data.enrollment

import android.content.ContentResolver
import android.provider.Settings
import com.orion.core.enrollment.DeviceIdProvider

/**
 * ANDROID_ID is an identifier, not an authentication factor. When the platform
 * returns null/blank, this returns `null` so enrollment is explicitly blocked
 * (DeviceIdUnavailable) -- never a shared placeholder that would make many
 * devices look like one.
 */
class AndroidDeviceIdProvider(private val contentResolver: ContentResolver) : DeviceIdProvider {
    override fun currentDeviceId(): String? =
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)?.takeIf { it.isNotBlank() }
}
