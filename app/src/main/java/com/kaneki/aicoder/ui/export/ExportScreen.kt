package com.kaneki.aicoder.ui.export

import com.kaneki.aicoder.domain.util.FormatUtils
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun ExportScreen(
    projectId: String,
    onBack: () -> Unit,
    viewModel: ExportViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(projectId) {
        viewModel.init(projectId)
        // Auto-build saat layar dibuka
        viewModel.buildZip()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Export", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onBack) { Text("Kembali") }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            "Project: ${projectId.take(8)}…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))

        when (val state = uiState) {
            is ExportUiState.Idle, is ExportUiState.Building -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Menyiapkan file export…")
                }
            }

            is ExportUiState.Ready -> {
                val isZip = state.mimeType == "application/zip" ||
                    state.displayName.lowercase().endsWith(".zip")
                Text(
                    if (isZip) "ZIP siap" else "File siap",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(8.dp))
                Text("Nama: ${state.displayName}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (isZip) {
                        "${state.fileCount} file · ${FormatUtils.formatByteSize(state.sizeBytes)}"
                    } else {
                        "Isi terbaru · ${FormatUtils.formatByteSize(state.sizeBytes)}"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(24.dp))

                Button(
                    onClick = { viewModel.saveToDownloads() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Simpan ke Downloads")
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        viewModel.createShareIntent()?.let { intent ->
                            context.startActivity(
                                Intent.createChooser(intent, "Bagikan file")
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Bagikan")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { viewModel.buildZip() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Bangun ulang")
                }
            }

            is ExportUiState.SavedToDownloads -> {
                Text(
                    "Tersimpan di Downloads",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                Text(state.displayName, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Cek folder Downloads di file manager device.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = {
                        viewModel.createShareIntent()?.let { intent ->
                            context.startActivity(
                                Intent.createChooser(intent, "Bagikan file")
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Bagikan juga")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Selesai")
                }
            }

            is ExportUiState.Error -> {
                Text(
                    state.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { viewModel.buildZip() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Coba lagi")
                }
            }
        }
    }
}

