package com.kaneki.aicoder.ui.upload

import com.kaneki.aicoder.domain.util.FormatUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kaneki.aicoder.data.local.db.ProjectEntity
import com.kaneki.aicoder.domain.android.AndroidProjectAnalyzer
import com.kaneki.aicoder.domain.model.FileTree
import com.kaneki.aicoder.ui.overview.FileTreeView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun UploadScreen(
    onOpenProjectChat: (projectId: String, fileTree: FileTree) -> Unit = { _, _ -> },
    onOpenSettings: () -> Unit = {},
    onBack: () -> Unit = {},
    viewModel: UploadViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val history by viewModel.projectHistory.collectAsState()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.onFileSelected(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        when (val state = uiState) {
            is UploadUiState.Idle -> {
                IdleContent(
                    history = history,
                    onPickFile = {
                        filePickerLauncher.launch(
                            arrayOf(
                                "application/zip",
                                "application/x-zip-compressed",
                                "text/*",
                                "application/json",
                                "application/xml",
                                "application/javascript",
                                "text/javascript",
                                "text/x-java-source",
                                "text/x-kotlin",
                                "text/markdown",
                                "text/plain",
                                "application/octet-stream"
                            )
                        )
                    },
                    onOpenProject = { viewModel.openProject(it) },
                    onDeleteProject = { viewModel.deleteProject(it) },
                    onOpenSettings = onOpenSettings,
                    onBack = onBack
                )
            }

            is UploadUiState.Extracting, is UploadUiState.LoadingProject -> {
                ExtractingContent(
                    message = if (state is UploadUiState.LoadingProject) {
                        "Membuka project…"
                    } else {
                        "Mengimpor file… (mungkin perlu waktu untuk file besar)"
                    }
                )
            }

            is UploadUiState.Success -> {
                SuccessContent(
                    state = state,
                    onImportAnother = { viewModel.reset() },
                    onOpenProjectChat = { onOpenProjectChat(state.projectId, state.fileTree) }
                )
            }

            is UploadUiState.Error -> {
                ErrorContent(
                    message = state.message,
                    onRetry = { viewModel.reset() }
                )
            }
        }
    }
}

@Composable
private fun IdleContent(
    history: List<ProjectEntity>,
    onPickFile: () -> Unit,
    onOpenProject: (String) -> Unit,
    onDeleteProject: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit = {}
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("‹ Batal")
            }
            Text("Import Project", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onOpenSettings) {
                Text("Pengaturan")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = history.isEmpty()),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Import project atau file",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "ZIP (project lengkap) atau file tunggal:\n" +
                        ".txt .md .kt .java .xml .json .py\n" +
                        "Maks. 30MB.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onPickFile) {
                    Text("Pilih ZIP / File")
                }
            }
        }

        if (history.isNotEmpty()) {
            Text(
                "Riwayat project",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(history, key = { it.id }) { project ->
                    ProjectHistoryCard(
                        project = project,
                        onOpen = { onOpenProject(project.id) },
                        onDelete = { onDeleteProject(project.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectHistoryCard(
    project: ProjectEntity,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val date = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())
        .format(Date(project.createdAt))
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(project.name, style = MaterialTheme.typography.titleSmall)
            Text(
                "${project.fileCount} file · $date",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDelete) {
                    Text("Hapus", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onOpen) {
                    Text("Buka")
                }
            }
        }
    }
}

@Composable
private fun ExtractingContent(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SuccessContent(
    state: UploadUiState.Success,
    onImportAnother: () -> Unit,
    onOpenProjectChat: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${state.fileTree.totalFileCount} file",
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    text = FormatUtils.formatByteSize(state.fileTree.totalSizeBytes),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        val androidAnalysis = AndroidProjectAnalyzer.analyzePaths(state.fileTree)
        if (androidAnalysis.isAndroidProject) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    Text(
                        text = "Project Android terdeteksi (${androidAnalysis.completenessScore}%)",
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (androidAnalysis.keyPaths.stringsXml.isNotEmpty()) {
                        Text(
                            text = "strings.xml: ${androidAnalysis.keyPaths.stringsXml.first()}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    androidAnalysis.keyPaths.manifest?.let { path ->
                        Text(
                            text = "Manifest: $path",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (androidAnalysis.issues.isNotEmpty()) {
                        Text(
                            text = "⚠ ${androidAnalysis.issues.first()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        Text(
            text = "Struktur Project",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(vertical = 4.dp)
        )
        FileTreeView(
            fileTree = state.fileTree,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
        Button(
            onClick = onOpenProjectChat,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        ) {
            Text("Mulai Chat (Tools)")
        }
        OutlinedButton(
            onClick = onImportAnother,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        ) {
            Text("Import File / Project Lain")
        }
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Gagal mengimpor project",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRetry) {
                Text("Coba Lagi")
            }
        }
    }
}

