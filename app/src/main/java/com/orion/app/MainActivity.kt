package com.orion.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

/**
 * Placeholder launch screen. Deliberately does not talk to RFID hardware,
 * localization, or navigation logic — see CLAUDE.md §12: the UI consumes
 * application/navigation state only, once that wiring exists.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                OrionPlaceholderScreen()
            }
        }
    }
}

@Composable
private fun OrionPlaceholderScreen() {
    Scaffold { padding ->
        Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Orion")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OrionPlaceholderScreenPreview() {
    MaterialTheme {
        OrionPlaceholderScreen()
    }
}
