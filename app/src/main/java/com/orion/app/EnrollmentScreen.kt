// ============================================================
// app/EnrollmentScreen.kt
// Consumes EnrollmentUiState only — no storage/network access here
// (CLAUDE.md §12, mirrors MainActivity.kt's OrionPlaceholderScreen doc note).
// ============================================================
package com.orion.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp

/**
 * Enrollment UI for a not-yet-enrolled, or no-longer-validly-enrolled, device.
 * Purely a function of [EnrollmentUiState] plus the [onEnroll] callback — it
 * never talks to [com.orion.core.enrollment.EnrollmentStore] or
 * [com.orion.core.enrollment.EnrollmentVerifier] directly.
 */
@Composable
fun EnrollmentScreen(
    state: EnrollmentUiState,
    onEnroll: (organizationCode: String, siteCode: String) -> Unit
) {
    var organizationCode by remember { mutableStateOf("") }
    var siteCode by remember { mutableStateOf("") }

    val deviceId = when (state) {
        is EnrollmentUiState.NeedsEnrollment -> state.deviceId
        is EnrollmentUiState.NeedsReEnrollment -> state.deviceId
        else -> null
    }

    Scaffold { padding ->
        Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text("Enroll this device", style = MaterialTheme.typography.headlineSmall)

                if (state is EnrollmentUiState.NeedsReEnrollment) {
                    Text(state.reason, modifier = Modifier.padding(top = 8.dp))
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
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    enabled = state !is EnrollmentUiState.Enrolling
                )

                OutlinedTextField(
                    value = siteCode,
                    onValueChange = { siteCode = it },
                    label = { Text("Store/site code") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    enabled = state !is EnrollmentUiState.Enrolling
                )

                Button(
                    onClick = { onEnroll(organizationCode, siteCode) },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    enabled = state !is EnrollmentUiState.Enrolling
                ) {
                    Text("Enroll")
                }

                when (state) {
                    is EnrollmentUiState.Enrolling -> Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Text("Verifying enrollment…", modifier = Modifier.padding(top = 8.dp))
                    }

                    is EnrollmentUiState.EnrollmentRejected -> Text(
                        "Enrollment was rejected: ${state.reason}",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 16.dp)
                    )

                    is EnrollmentUiState.EnrollmentFailed -> Text(
                        "Enrollment failed: ${state.reason}",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 16.dp)
                    )

                    else -> Unit
                }
            }
        }
    }
}
