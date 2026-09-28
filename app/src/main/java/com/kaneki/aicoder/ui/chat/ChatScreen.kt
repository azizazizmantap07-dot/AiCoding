package com.kaneki.aicoder.ui.chat

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import android.graphics.BitmapFactory
import com.kaneki.aicoder.domain.model.ImageAttachment
import com.kaneki.aicoder.domain.util.ImageEncoder
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kaneki.aicoder.data.remote.AiProvider
import com.kaneki.aicoder.data.local.ZipFileStore
import com.kaneki.aicoder.data.local.db.AppDatabase
import com.kaneki.aicoder.data.local.db.ProjectEntity
import com.kaneki.aicoder.data.repository.ChatRepository
import com.kaneki.aicoder.domain.model.FileTree
import com.kaneki.aicoder.domain.model.PendingChange
import com.kaneki.aicoder.ui.diff.DiffReviewScreen
import com.kaneki.aicoder.ui.export.ExportViewModel
import com.kaneki.aicoder.domain.export.QuickExportResult
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    projectId: String?,
    fileTree: FileTree,
    onBack: () -> Unit,
    onOpenExport: () -> Unit = {},
    onImportProject: () -> Unit = {},
    onNewProject: () -> Unit = {},
    onOpenProject: (String) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    viewModel: ChatViewModel = viewModel(),
    exportViewModel: ExportViewModel = viewModel()
) {
    val hasProject = projectId != null

    LaunchedEffect(projectId) {
        viewModel.initProject(projectId, fileTree)
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    // Daftar project untuk panel riwayat di drawer (garis 3 kiri atas)
    val appContext = context.applicationContext as android.app.Application
    val projectDao = remember { AppDatabase.getInstance(appContext).projectDao() }
    val zipFileStore = remember { ZipFileStore(appContext) }
    val chatRepository = remember { ChatRepository(AppDatabase.getInstance(appContext).chatDao()) }
    var projectHistory by remember { mutableStateOf<List<ProjectEntity>>(emptyList()) }
    LaunchedEffect(Unit) {
        projectDao.observeAll().collectLatest { projectHistory = it }
    }

    var isExporting by remember { mutableStateOf(false) }

    val messages by viewModel.messages.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val toolActivity by viewModel.toolActivity.collectAsState()
    val pendingChanges by viewModel.pendingChanges.collectAsState()
    val tokenBudget by viewModel.tokenBudget.collectAsState()
    val streamingText by viewModel.streamingText.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val selectedModelLabel by viewModel.selectedModelLabel.collectAsState()
    val selectedProvider by viewModel.selectedProvider.collectAsState()
    val androidInfo by viewModel.androidInfo.collectAsState()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var pendingImageUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    val supportsVision by viewModel.selectedModelSupportsVision.collectAsState()
    val selectedModelSupportsImageGen by viewModel.selectedModelSupportsImageGen.collectAsState()
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 4)
    ) { uris ->
        if (uris.isNotEmpty()) {
            pendingImageUris = (pendingImageUris + uris).distinct().take(4)
        }
    }
    var showDiffReview by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val isLoading = uiState is ChatUiState.Loading

    fun quickExport() {
        val pid = projectId ?: return
        if (isExporting) return
        isExporting = true
        scope.launch {
            when (val result = exportViewModel.buildAndGetShareIntent(pid)) {
                is QuickExportResult.Ready -> {
                    context.startActivity(Intent.createChooser(result.intent, "Bagikan file hasil edit"))
                }
                is QuickExportResult.Error -> {
                    Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                }
            }
            isExporting = false
        }
    }

    if (showDiffReview && pendingChanges.isNotEmpty() && projectId != null) {
        DiffReviewScreen(
            projectId = projectId,
            pendingChanges = pendingChanges,
            onBack = { showDiffReview = false },
            onDone = {
                viewModel.clearPending()
                viewModel.refreshFileTree()
                showDiffReview = false
            }
        )
        return
    }

    if (showModelPicker) {
        ModelPickerSheet(
            currentProvider = selectedProvider,
            currentModel = selectedModel,
            apiKeyForProvider = { provider -> viewModel.getApiKeyFor(provider) },
            onDismiss = { showModelPicker = false },
            onSelect = { provider, modelId, label, contextLimit, supportsVision, supportsImageGen ->
                viewModel.setSelectedProviderAndModel(
                    provider, modelId, label, contextLimit, supportsVision, supportsImageGen
                )
                showModelPicker = false
            }
        )
    }

    LaunchedEffect(messages.size, toolActivity, streamingText) {
        if (messages.isNotEmpty() || toolActivity != null) {
            listState.animateScrollToItem(
                (messages.size + if (toolActivity != null) 1 else 0).coerceAtLeast(0)
            )
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ChatDrawerContent(
                projects = projectHistory,
                activeProjectId = projectId,
                onNewProject = {
                    scope.launch { drawerState.close() }
                    onNewProject()
                },
                onOpenProject = { id ->
                    scope.launch { drawerState.close() }
                    if (id != projectId) onOpenProject(id)
                },
                onDeleteProject = { id ->
                    val wasActive = id == projectId
                    scope.launch {
                        chatRepository.clearProject(id)
                        zipFileStore.deleteProject(id)
                        projectDao.deleteById(id)
                        drawerState.close()
                        if (wasActive) onBack()
                    }
                },
                onOpenSettings = {
                    scope.launch { drawerState.close() }
                    onOpenSettings()
                },
                onClearActiveChat = {
                    scope.launch { drawerState.close() }
                    viewModel.clearChat()
                }
            )
        }
    ) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            if (hasProject) "Chat + Tools" else "Chat",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            if (hasProject) {
                                val androidLabel = androidInfo?.let { info ->
                                    val warn = if (info.issues.isNotEmpty()) " ⚠" else ""
                                    " · Android ${info.score}%$warn"
                                } ?: ""
                                "${fileTree.totalFileCount} file · ${projectId!!.take(8)}…$androidLabel"
                            } else {
                                "Tanpa project — buka menu untuk import"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                    }
                },
                actions = {
                    if (hasProject) {
                        IconButton(onClick = { quickExport() }, enabled = !isExporting) {
                            if (isExporting) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            } else {
                                Icon(Icons.Default.IosShare, contentDescription = "Export & Bagikan")
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        modifier = Modifier.imePadding()
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
        ) {
            TokenBudgetBar(tokenBudget)

            if (selectedModelSupportsImageGen) {
                Text(
                    "Mode 🖼 Generate gambar aktif — ketik prompt lalu kirim",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                )
            } else if (supportsVision) {
                Text(
                    "Model mendukung 👁 Vision — bisa lampirkan foto",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            if (messages.isEmpty() && !isLoading) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Mulai percakapan",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (hasProject) {
                                "Contoh: “List struktur project” atau\n“Baca MainActivity dan jelaskan”"
                            } else {
                                "Tanya apa saja, atau buka menu ☰ dan pilih\n“Import Project” untuk mulai edit kode"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages) { item ->
                        when (item) {
                            is ChatDisplayItem.UserText -> Bubble(
                                text = item.text,
                                isUser = true,
                                imageUris = item.imageUris
                            )
                            is ChatDisplayItem.AssistantText -> Bubble(text = item.text, isUser = false)
                            is ChatDisplayItem.AssistantImage -> GeneratedImageBubble(
                                path = item.localPath,
                                caption = item.caption
                            )
                        }
                    }
                    if (streamingText.isNotBlank()) {
                        item {
                            Bubble(text = streamingText + " ▍", isUser = false)
                        }
                    }
                    if (toolActivity != null) {
                        item { ToolActivityIndicator(message = toolActivity) }
                    }
                    if (isLoading && toolActivity == null && streamingText.isBlank()) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                Spacer(Modifier.padding(6.dp))
                                Text("Memproses…", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            if (uiState is ChatUiState.Error) {
                Text(
                    (uiState as ChatUiState.Error).message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 140.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 4.dp)
                )
            }

            if (pendingChanges.isNotEmpty()) {
                PendingChangesCard(
                    changes = pendingChanges,
                    onReview = { showDiffReview = true }
                )
            }

            Spacer(Modifier.height(8.dp))

            if (pendingImageUris.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    pendingImageUris.forEach { uri ->
                        Box {
                            AttachmentThumb(uriStr = uri.toString())
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.errorContainer)
                                    .clickable {
                                        pendingImageUris = pendingImageUris.filter { it != uri }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Hapus",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            // Kolom input gaya pill (mirip preferensi modern):
            // [+]  Tanyakan apa saja…                    [model] [kirim]
            val barShape = RoundedCornerShape(28.dp)
            val barBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clip(barShape)
                    .background(barBg)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                        shape = barShape
                    )
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Import ZIP / file
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(enabled = !isLoading, onClick = onImportProject),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Import ZIP / File",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Lampirkan gambar (vision)
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(enabled = !isLoading) {
                            if (!supportsVision) {
                                Toast.makeText(
                                    context,
                                    "Model aktif tidak mendukung vision. Pilih model bertanda Vision.",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                photoPicker.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Image,
                        contentDescription = "Lampirkan gambar",
                        tint = if (supportsVision) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
                        }
                    )
                }

                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp, vertical = 10.dp),
                    enabled = !isLoading,
                    maxLines = 5,
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if ((input.isNotBlank() || pendingImageUris.isNotEmpty()) && !isLoading) {
                                val t = input
                                val uris = pendingImageUris.toList()
                                input = ""
                                pendingImageUris = emptyList()
                                focusManager.clearFocus()
                                if (uris.isEmpty()) {
                                    viewModel.sendMessage(t)
                                } else {
                                    scope.launch {
                                        val encoded = mutableListOf<ImageAttachment>()
                                        for (uri in uris) {
                                            ImageEncoder.encodeUri(context, uri)
                                                .onSuccess { encoded.add(it) }
                                                .onFailure { e ->
                                                    Toast.makeText(
                                                        context,
                                                        e.message ?: "Gagal memuat gambar",
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                }
                                        }
                                        if (encoded.isNotEmpty() || t.isNotBlank()) {
                                            viewModel.sendMessage(t, encoded)
                                        }
                                    }
                                }
                            }
                        }
                    ),
                    decorationBox = { inner ->
                        Box {
                            if (input.isEmpty()) {
                                Text(
                                    when {
                                        selectedModelSupportsImageGen -> "Deskripsikan gambar yang ingin dibuat…"
                                        hasProject -> "Tanyakan apa saja…"
                                        else -> "Tanyakan apa saja, atau import file…"
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                            inner()
                        }
                    }
                )

                // Pilih model AI (ikon bulat → popup ModelPickerSheet)
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(enabled = !isLoading) { showModelPicker = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = "Model: ${selectedProvider.displayName} · $selectedModelLabel",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(Modifier.size(2.dp))

                // Kirim / batalkan
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .clickable { viewModel.cancelGeneration() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Batalkan",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                } else {
                    val canSend = input.isNotBlank() || pendingImageUris.isNotEmpty()
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(
                                if (canSend) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                            )
                            .clickable(enabled = canSend) {
                                val t = input
                                val uris = pendingImageUris.toList()
                                input = ""
                                pendingImageUris = emptyList()
                                focusManager.clearFocus()
                                if (uris.isEmpty()) {
                                    viewModel.sendMessage(t)
                                } else {
                                    scope.launch {
                                        val encoded = mutableListOf<ImageAttachment>()
                                        for (uri in uris) {
                                            val result = ImageEncoder.encodeUri(context, uri)
                                            result.onSuccess { encoded.add(it) }
                                            result.onFailure { e ->
                                                Toast.makeText(
                                                    context,
                                                    e.message ?: "Gagal memuat gambar",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        }
                                        if (encoded.isNotEmpty() || t.isNotBlank()) {
                                            viewModel.sendMessage(t, encoded)
                                        }
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Kirim",
                            tint = if (canSend) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            },
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
    }
}



@Composable
private fun GeneratedImageBubble(path: String, caption: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val file = remember(path) { java.io.File(path) }
    val bitmap = remember(path) {
        runCatching { android.graphics.BitmapFactory.decodeFile(path) }.getOrNull()
    }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                if (bitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = caption,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(
                        "Gambar tersimpan: ${file.name}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (caption.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        caption,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            if (isSaving) return@TextButton
                            isSaving = true
                            saveMessage = null
                            scope.launch(Dispatchers.IO) {
                                val result = com.kaneki.aicoder.domain.util.ImageGallerySaver
                                    .saveImageFile(context, path)
                                withContext(Dispatchers.Main) {
                                    isSaving = false
                                    saveMessage = when (result) {
                                        is com.kaneki.aicoder.domain.util.ImageGallerySaver.Result.Success ->
                                            "Tersimpan di Galeri · Pictures/AI Coder"
                                        is com.kaneki.aicoder.domain.util.ImageGallerySaver.Result.Error ->
                                            result.message
                                    }
                                }
                            }
                        },
                        enabled = !isSaving && file.exists()
                    ) {
                        Text(if (isSaving) "Menyimpan…" else "Simpan ke Galeri")
                    }
                }
                saveMessage?.let { msg ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        msg,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (msg.startsWith("Tersimpan"))
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}


@Composable
private fun Bubble(
    text: String,
    isUser: Boolean,
    imageUris: List<String> = emptyList()
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .background(
                    color = if (isUser) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(12.dp)
        ) {
            Column {
                if (imageUris.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = if (text.isNotBlank()) 8.dp else 0.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        imageUris.forEach { uriStr ->
                            AttachmentThumb(uriStr = uriStr)
                        }
                    }
                }
                if (text.isNotBlank()) {
                    if (isUser) {
                        Text(text, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        MarkdownText(
                            markdown = text,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentThumb(uriStr: String) {
    val context = LocalContext.current
    val bitmap = remember(uriStr) {
        runCatching {
            context.contentResolver.openInputStream(Uri.parse(uriStr))?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Lampiran gambar",
            modifier = Modifier
                .size(120.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Image, contentDescription = null)
        }
    }
}

@Composable
private fun PendingChangesCard(
    changes: List<PendingChange>,
    onReview: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "${changes.size} file di staging (belum di-apply)",
                style = MaterialTheme.typography.titleSmall
            )
            changes.take(6).forEach { c ->
                Text("• ${c.path}", style = MaterialTheme.typography.bodySmall)
            }
            if (changes.size > 6) {
                Text("… dan ${changes.size - 6} lainnya", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onReview, modifier = Modifier.fillMaxWidth()) {
                Text("Lihat & Terapkan Diff")
            }
        }
    }
}

@Composable
private fun TokenBudgetBar(budget: TokenBudgetUi) {
    // Selalu tampilkan agar user tahu sisa token real-time sampai limit
    var expanded by remember { mutableStateOf(false) }

    // Provider/model aktif tidak mengembalikan context length sama sekali —
    // tampilkan status "belum ada info" secara jujur, JANGAN jatuh ke angka
    // statis (mis. 900rb) yang terkesan seperti data aktual padahal tebakan.
    if (!budget.hasKnownLimit) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .clickable { expanded = !expanded },
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            ),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Info token",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            if (budget.usedTokens > 0)
                                "~${formatTokenCount(budget.usedTokens)} terpakai · limit belum pasti"
                            else
                                "Limit model belum diketahui",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "—",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (expanded) {
                    Spacer(Modifier.height(10.dp))
                    TokenDetailRow(
                        "Terpakai",
                        if (budget.usedTokens > 0) formatTokenCount(budget.usedTokens) else "—"
                    )
                    TokenDetailRow("Limit maksimal", "Tidak diketahui")
                    TokenDetailRow(
                        "Sumber hitung",
                        when {
                            budget.fromApi -> "Data API (akurat)"
                            budget.usedTokens > 0 -> "Estimasi lokal"
                            else -> "Belum ada pemakaian"
                        }
                    )
                    if (budget.compacted) {
                        TokenDetailRow("History", "Otomatis diringkas agar hemat token")
                    }
                    Text(
                        "Provider/model ini tidak mengembalikan info context window, " +
                            "jadi limit & sisa token tidak bisa ditampilkan.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 6.dp)
                    )
                } else {
                    Text(
                        "Ketuk untuk detail · limit tidak diketahui",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
        return
    }

    val remaining = budget.remainingTokens ?: 0
    val remainingPct = budget.remainingPercent ?: 0

    val barProgress by animateFloatAsState(
        targetValue = (remainingPct / 100f).coerceIn(0f, 1f),
        label = "tokenRemaining"
    )
    val barColor by animateColorAsState(
        targetValue = when {
            remainingPct <= 10 -> MaterialTheme.colorScheme.error
            remainingPct <= 25 -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.primary
        },
        label = "tokenColor"
    )
    val statusText = when {
        remainingPct <= 10 -> "Hampir habis"
        remainingPct <= 25 -> "Tersisa sedikit"
        remainingPct <= 50 -> "Masih cukup"
        else -> "Aman"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Sisa token",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        formatTokenCount(remaining),
                        style = MaterialTheme.typography.titleMedium,
                        color = barColor
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        statusText,
                        style = MaterialTheme.typography.labelLarge,
                        color = barColor
                    )
                    Text(
                        "${remainingPct}% tersisa",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Progress = sisa (penuh = banyak sisa, kosong = hampir habis)
            LinearProgressIndicator(
                progress = { barProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = barColor,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
            )

            if (expanded) {
                Spacer(Modifier.height(10.dp))
                TokenDetailRow("Terpakai", formatTokenCount(budget.usedTokens))
                TokenDetailRow("Limit maksimal", formatTokenCount(budget.maxTokens ?: 0))
                TokenDetailRow("Sisa sampai limit", formatTokenCount(remaining))
                TokenDetailRow(
                    "Sumber hitung",
                    when {
                        budget.fromApi -> "Data API (akurat)"
                        budget.usedTokens > 0 -> "Estimasi lokal"
                        else -> "Belum ada pemakaian"
                    }
                )
                if (budget.compacted) {
                    TokenDetailRow("History", "Otomatis diringkas agar hemat token")
                }
                Text(
                    "Ketuk lagi untuk menutup detail",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 6.dp)
                )
            } else {
                Text(
                    "Ketuk untuk detail · limit ${formatTokenCount(budget.maxTokens ?: 0)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun TokenDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/** Format angka token agar mudah dibaca: 900000 → 900 rb, 1500 → 1,5 rb */
private fun formatTokenCount(n: Int): String {
    val v = n.coerceAtLeast(0)
    return when {
        v >= 1_000_000 -> {
            val m = v / 1_000_000.0
            if (m == m.toInt().toDouble()) "${m.toInt()} jt" else "%.1f jt".format(m)
        }
        v >= 1_000 -> {
            val k = v / 1_000.0
            if (k == k.toInt().toDouble()) "${k.toInt()} rb" else "%.1f rb".format(k)
        }
        else -> "$v"
    }
}
