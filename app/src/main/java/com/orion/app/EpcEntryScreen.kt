// ============================================================
// app/EpcEntryScreen.kt
// Consumes FindUiState only — no RFID SDK, lookup, or ViewModel access here
// (CLAUDE.md §12), mirroring EnrollmentScreen.kt's convention of a pure
// function of UI state plus callbacks.
//
// Scope: typed-EPC entry and search-by-name only. There is no real
// scanner/RFID hardware integration in this codebase yet, so this screen
// does not offer a "Scan" affordance that would fake one — FindInput.ScannedEpc
// stays unused until real scan hardware exists (see MainActivity.kt).
//
// This screen never renders FindUiState.Navigating — the caller (MainActivity)
// routes that state to CompassScreen instead.
// ============================================================
package com.orion.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.orion.core.inventory.EpcTarget

/**
 * Entry point for the Find flow: typed-EPC lookup and search-by-name, plus the
 * candidate-picker (search results) and resolution failure states. Purely a
 * function of [FindUiState] plus callbacks — it never talks to
 * [com.orion.core.inventory.EpcLookup] or [com.orion.core.rfid.RfidReader] directly.
 */
@Composable
fun EpcEntryScreen(
    state: FindUiState,
    onResolutionScreenOpened: () -> Unit,
    onTypedEpc: (String) -> Unit,
    onSearch: (String) -> Unit,
    onEpcChosen: (EpcTarget) -> Unit
) {
    LaunchedEffect(Unit) { onResolutionScreenOpened() }

    var epcInput by remember { mutableStateOf("") }
    var searchInput by remember { mutableStateOf("") }

    val busy = state is FindUiState.Resolving
    val inputsEnabled = !busy

    Scaffold { padding ->
        Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text("Find an item", style = MaterialTheme.typography.headlineSmall)

                OutlinedTextField(
                    value = epcInput,
                    onValueChange = { epcInput = it },
                    label = { Text("EPC") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    enabled = inputsEnabled
                )
                Button(
                    onClick = { onTypedEpc(epcInput) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    enabled = inputsEnabled && epcInput.isNotBlank()
                ) {
                    Text("Find")
                }

                HorizontalDivider(modifier = Modifier.padding(top = 24.dp, bottom = 24.dp))

                OutlinedTextField(
                    value = searchInput,
                    onValueChange = { searchInput = it },
                    label = { Text("Search by name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = inputsEnabled
                )
                Button(
                    onClick = { onSearch(searchInput) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    enabled = inputsEnabled && searchInput.isNotBlank()
                ) {
                    Text("Search")
                }

                when (state) {
                    is FindUiState.Resolving -> Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Text("Looking up…", modifier = Modifier.padding(top = 8.dp))
                    }

                    is FindUiState.PickEpc -> CandidateList(
                        candidates = state.candidates,
                        onEpcChosen = onEpcChosen
                    )

                    is FindUiState.NotFound -> Text(
                        "No item found for \"${state.epc}\".",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 16.dp)
                    )

                    is FindUiState.Invalid -> Text(
                        "That EPC isn't valid: ${state.reason}",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 16.dp)
                    )

                    is FindUiState.Error -> Text(
                        "Lookup failed: ${state.message}",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 16.dp)
                    )

                    // Idle: nothing extra to show below the form.
                    // Navigating: not rendered here — MainActivity routes to CompassScreen instead.
                    is FindUiState.Idle, is FindUiState.Navigating -> Unit
                }
            }
        }
    }
}

@Composable
private fun CandidateList(
    candidates: List<EpcTarget>,
    onEpcChosen: (EpcTarget) -> Unit
) {
    Text(
        "Select an item:",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp)
    )
    LazyColumn(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        items(candidates) { candidate ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                onClick = { onEpcChosen(candidate) }
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        candidate.displayName ?: candidate.epc,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    if (candidate.displayName != null) {
                        Text(
                            candidate.epc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
