package com.kaneki.aicoder.ui.diff

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import com.kaneki.aicoder.domain.model.PendingChange

@Composable
fun DiffReviewScreen(
    projectId: String,
    pendingChanges: List<PendingChange>,
    onBack: () -> Unit,
    onDone: () -> Unit = {},
    viewModel: DiffViewModel = viewModel()
) {
    LaunchedEffect(projectId, pendingChanges) {
        viewModel.init(projectId, pendingChanges)
    }

    val context = LocalContext.current
    val changes by viewModel.changes.collectAsState()
    val selectedPath by viewModel.selectedPath.collectAsState()
    val diffCache by viewModel.diffCache.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Diff Review", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onBack) { Text("Kembali") }
        }

        Text(
            "${changes.count { it.approved }}/${changes.size} disetujui",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(8.dp))

        // Daftar file
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
        ) {
            items(changes, key = { it.path }) { change ->
                FileRow(
                    change = change,
                    selected = change.path == selectedPath,
                    onSelect = { viewModel.selectFile(change.path) },
                    onToggle = { viewModel.toggleApproved(change.path) }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = { viewModel.approveAll() }) { Text("Terima semua") }
            OutlinedButton(onClick = { viewModel.rejectAll() }) { Text("Tolak semua") }
        }

        Spacer(Modifier.height(8.dp))

        // Diff content (preview kode hasil edit)
        val lines = selectedPath?.let { diffCache[it] }.orEmpty()
        Text(
            selectedPath ?: "(pilih file)",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
        ) {
            if (lines.isEmpty() && selectedPath != null) {
                item {
                    Text(
                        "Menghitung diff…",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            items(lines.size) { idx ->
                DiffLineView(line = lines[idx], filePath = selectedPath.orEmpty())
            }
        }

        Spacer(Modifier.height(8.dp))

        when (val state = uiState) {
            is DiffUiState.Applying -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Menerapkan…")
                }
            }
            is DiffUiState.Saving -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(state.message)
                }
            }
            is DiffUiState.SaveReady -> {
                Text(
                    state.message,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Cek folder Downloads di file manager device.",
                    style = MaterialTheme.typography.bodySmall
                )
                if (changes.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent.createChooser(state.shareIntent, "Bagikan file hasil edit")
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Bagikan juga")
                    }
                    OutlinedButton(
                        onClick = onDone,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    ) {
                        Text("Kembali ke Chat")
                    }
                }
            }
            is DiffUiState.Error -> {
                Text(state.message, color = MaterialTheme.colorScheme.error)
            }
            else -> {}
        }

        if (changes.isNotEmpty()) {
            Button(
                onClick = { viewModel.applyAndSave() },
                enabled = uiState !is DiffUiState.Applying &&
                    uiState !is DiffUiState.Saving &&
                    changes.any { it.approved },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Terapkan & Simpan File")
            }
        }
    }
}

@Composable
private fun FileRow(
    change: PendingChange,
    selected: Boolean,
    onSelect: () -> Unit,
    onToggle: () -> Unit
) {
    val badge = when {
        change.isRenamed -> " [rename dari ${change.oldPath}]"
        change.isNewFile -> " [baru]"
        change.isDeleted -> " [hapus]"
        else -> ""
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                else MaterialTheme.colorScheme.surface
            )
            .clickable(onClick = onSelect)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = change.approved,
            onCheckedChange = { onToggle() }
        )
        Text(
            change.path + badge,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
    }
}
