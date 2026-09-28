package com.kaneki.aicoder.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kaneki.aicoder.data.remote.AiProvider

@Composable
fun ApiKeySetupScreen(
    onKeySaved: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val editingProvider by viewModel.editingProvider.collectAsState()
    val hasKey by viewModel.hasApiKey.collectAsState()
    val context = LocalContext.current
    var keyInput by remember { mutableStateOf("") }

    LaunchedEffect(uiState) {
        if (uiState is ApiKeySetupUiState.Saved) {
            onKeySaved()
        }
    }

    // Ganti tab provider -> input key dikosongkan lagi, supaya tidak
    // ke-submit ke provider yang salah kalau user lupa mengosongkan dulu.
    LaunchedEffect(editingProvider) {
        keyInput = ""
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "API Key",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Disimpan terenkripsi di perangkat ini.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))
            Text("Pilih provider", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AiProvider.entries.forEach { provider ->
                    FilterChip(
                        selected = editingProvider == provider,
                        onClick = {
                            viewModel.setEditingProvider(provider)
                        },
                        label = { Text(provider.displayName) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = editingProvider.shortDescription,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = editingProvider.freeTierNote,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (hasKey) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "✓ Key untuk ${editingProvider.displayName} sudah tersimpan.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API Key ${editingProvider.displayName}") },
                placeholder = {
                    if (editingProvider == AiProvider.CLOUDFLARE) {
                        Text("ACCOUNT_ID:API_TOKEN")
                    }
                },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                enabled = uiState !is ApiKeySetupUiState.Validating
            )

            Spacer(modifier = Modifier.height(12.dp))

            when (val state = uiState) {
                is ApiKeySetupUiState.Error -> {
                    Text(
                        text = state.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                is ApiKeySetupUiState.Validating -> {
                    CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                }
                else -> {}
            }

            Button(
                onClick = { viewModel.submitKey(keyInput) },
                modifier = Modifier.fillMaxWidth(),
                enabled = keyInput.isNotBlank() && uiState !is ApiKeySetupUiState.Validating
            ) {
                Text("Simpan")
            }

            TextButton(
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(editingProvider.getKeyUrl))
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Dapatkan gratis di ${editingProvider.displayName}")
            }

            if (hasKey) {
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(
                    onClick = { viewModel.clearApiKey() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Hapus key")
                }
            }
        }
    }
}
