// ============================================================
// app/EnrollmentScreen.kt
// Consumes DeviceAuthorizationState only — no storage/network access here
// (CLAUDE.md §12).
// ============================================================
package com.orion.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.orion.core.enrollment.DeviceAuthorizationState

/**
 * Enrollment form for a not-yet-enrolled device, or controlled re-enrollment
 * after an administrator reset (invalid credential / unknown device / recovery
 * required). Purely a function of [state] plus [onEnroll].
 */
@Composable
fun EnrollmentScreen(
    state: DeviceAuthorizationState,
    deviceId: String?,
    onEnroll: (organizationCode: String, siteCode: String, enrollmentAuthorization: String) -> Unit
) {
    var organizationCode by remember { mutableStateOf("") }
    var siteCode by remember { mutableStateOf("") }
    // Plain remember (not rememberSaveable): the one-time secret must not be written
    // into the saved-instance-state Bundle.
    var enrollmentAuthorization by remember { mutableStateOf("") }

    val enrolling = state is DeviceAuthorizationState.Enrolling

    Scaffold { padding ->
        Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text("Enroll this device", style = MaterialTheme.typography.headlineSmall)

                contextMessage(state)?.let {
                    Text(it, modifier = Modifier.padding(top = 8.dp))
                }

                if (deviceId != null) {
                    Text(
                        "Device ID: $deviceId",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }

                OutlinedTextField(
                    value = organizationCode,
                    onValueChange = { organizationCode = it },
                    label = { Text("Organization code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    enabled = !enrolling
                )

                OutlinedTextField(
                    value = siteCode,
                    onValueChange = { siteCode = it },
                    label = { Text("Store/site code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    enabled = !enrolling
                )

                OutlinedTextField(
                    value = enrollmentAuthorization,
                    onValueChange = { enrollmentAuthorization = it },
                    label = { Text("Enrollment auth. code") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    enabled = !enrolling
                )

                Button(
                    onClick = { onEnroll(organizationCode, siteCode, enrollmentAuthorization) },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    enabled = !enrolling &&
                        organizationCode.isNotBlank() &&
                        siteCode.isNotBlank() &&
                        enrollmentAuthorization.isNotBlank()
                ) {
                    Text("Enroll")
                }

                when (state) {
                    is DeviceAuthorizationState.Enrolling -> Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Text("Enrolling… (requires a network connection)", modifier = Modifier.padding(top = 8.dp))
                    }

                    is DeviceAuthorizationState.EnrollmentRejected -> ErrorText("Enrollment was rejected: ${state.reason}")
                    is DeviceAuthorizationState.EnrollmentFailed -> ErrorText("Enrollment failed: ${state.reason}")
                    is DeviceAuthorizationState.RecoveryRequired -> ErrorText(state.reason)
                    else -> Unit
                }
            }
        }
    }
}

private fun contextMessage(state: DeviceAuthorizationState): String? = when (state) {
    DeviceAuthorizationState.InvalidCredential ->
        "This device's enrollment is no longer accepted. If an administrator has reset this " +
            "device, enter the codes and the new enrollment auth. code they provided."
    DeviceAuthorizationState.UnknownDevice ->
        "The enrollment service does not recognise this device. Ask an administrator for an " +
            "enrollment auth. code, then enroll again."
    is DeviceAuthorizationState.RecoveryRequired ->
        "After an administrator resets this device, enter the new enrollment auth. code."
    else -> null
}

@Composable
private fun ErrorText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 16.dp))
}
