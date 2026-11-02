package com.orion.core.enrollment

import kotlinx.coroutines.flow.MutableStateFlow

/** Mutable authorization gate for navigation tests. */
class FakeDeviceAccessGate : DeviceAccessGate {
    override val state = MutableStateFlow<DeviceAuthorizationState>(DeviceAuthorizationState.Authorized(Long.MAX_VALUE))
    override fun isAccessGranted(): Boolean = state.value.grantsAccess
}
