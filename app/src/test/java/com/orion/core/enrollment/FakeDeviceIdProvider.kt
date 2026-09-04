package com.orion.core.enrollment

class FakeDeviceIdProvider(private val deviceId: String = "test-device-id") : DeviceIdProvider {
    override fun currentDeviceId(): String = deviceId
}
