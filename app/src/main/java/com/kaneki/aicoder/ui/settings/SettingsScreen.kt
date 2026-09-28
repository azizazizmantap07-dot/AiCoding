package com.kaneki.aicoder.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kaneki.aicoder.data.local.SecurePrefs
import com.kaneki.aicoder.data.local.ThemeMode
import com.kaneki.aicoder.data.remote.AiProvider

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onChangeApiKey: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val themeMode by viewModel.themeMode.collectAsState()
    val context = LocalContext.current
    // Baca langsung dari SecurePrefs (bukan lewat editingProvider viewModel)
    // supaya status tiap provider di ringkasan ini tidak berubah kalau user
    // sebelumnya sempat mengganti tab provider yang sedang diedit.
    val securePrefs = remember(context) { SecurePrefs(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Pengaturan", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onBack) { Text("Kembali") }
        }

        Spacer(Modifier.height(24.dp))

        Text("Tema", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))

        Column(Modifier.selectableGroup()) {
            ThemeOption(
                label = "Ikuti sistem",
                selected = themeMode == ThemeMode.SYSTEM,
                onClick = { viewModel.setThemeMode(ThemeMode.SYSTEM) }
            )
            ThemeOption(
                label = "Terang",
                selected = themeMode == ThemeMode.LIGHT,
                onClick = { viewModel.setThemeMode(ThemeMode.LIGHT) }
            )
            ThemeOption(
                label = "Gelap",
                selected = themeMode == ThemeMode.DARK,
                onClick = { viewModel.setThemeMode(ThemeMode.DARK) }
            )
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(24.dp))

        Text("API Key", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(12.dp))

        AiProvider.entries.forEach { provider ->
            val hasKey = securePrefs.hasApiKey(provider)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(provider.displayName, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (hasKey) "Tersimpan" else "Belum ada",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hasKey) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onChangeApiKey,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Atur API Key")
        }
    }
}

@Composable
private fun ThemeOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}
