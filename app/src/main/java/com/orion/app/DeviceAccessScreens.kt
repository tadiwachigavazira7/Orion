// ============================================================
// app/DeviceAccessScreens.kt
// Non-form device-authorization screens (checking / blocked) and the
// offline banner. Pure functions of their parameters (CLAUDE.md §12).
// ============================================================
package com.orion.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun CheckingDeviceScreen() {
    Scaffold { padding ->
        Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
                Text("Verifying this device…", modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

/** Find/navigation is unavailable; explains why and optionally offers a manual re-check. */
@Composable
fun DeviceBlockedScreen(
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Scaffold { padding ->
        Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Text(message, modifier = Modifier.padding(top = 8.dp))
                if (actionLabel != null && onAction != null) {
                    Button(onClick = onAction, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                        Text(actionLabel)
                    }
                }
            }
        }
    }
}

/**
 * Shown while access comes from the server-issued offline allowance. [verificationPending]
 * = no verification has completed yet since launch (vs. the latest one having failed).
 */
@Composable
fun OfflineAccessBanner(verificationPending: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            if (verificationPending) {
                "Verifying this device… Using its offline access in the meantime."
            } else {
                "Offline: this device could not be verified just now. Access continues only for " +
                    "the limited time the enrollment service allowed; connect to keep using Orion."
            },
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            style = MaterialTheme.typography.bodySmall
        )
    }
}
