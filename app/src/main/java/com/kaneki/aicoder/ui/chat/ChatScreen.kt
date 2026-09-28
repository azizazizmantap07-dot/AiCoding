package com.kaneki.aicoder.ui.chat

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            if (hasProject) {
                                val androidLabel = androidInfo?.let { info ->
                                    val warn = if (info.issues.isNotEmpty()) " ⚠" else ""
                                    " · Android ${info.score}%$warn"
                                } ?: ""
                                "${fileTree.totalFileCount} file$androidLabel"
                            } else {
                                "Tanpa project"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                    }
                },
                actions = {
                    // Indikator token sebaris dengan tombol hamburger. Karena berada
                    // di deretan actions tepat SEBELUM tombol bagikan, posisinya
                    // otomatis bergeser ke kiri saat project diimpor (tombol bagikan
                    // muncul) dan kembali ke tepi kanan saat belum ada project.
                    val tokenEndPadding by animateDpAsState(
                        targetValue = if (hasProject) 0.dp else 6.dp,
                        label = "tokenIndicatorEndPadding"
                    )
                    TokenRingIndicator(
                        budget = tokenBudget,
                        modifier = Modifier.padding(end = tokenEndPadding)
                    )
                    AnimatedVisibility(
                        visible = hasProject,
                        enter = fadeIn() + expandHorizontally(),
                        exit = fadeOut() + shrinkHorizontally()
                    ) {
                        IconButton(onClick = { quickExport() }, enabled = !isExporting) {
                            if (isExporting) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            } else {
                                Icon(Icons.Default.IosShare, contentDescription = "Export")
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
            if (selectedModelSupportsImageGen) {
                Text(
                    "🖼 Mode generate gambar",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp)
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
                            "Mulai chat",
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (hasProject) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Contoh: “List struktur project”",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 24.dp)
                            )
                        }
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
            // [+] [gambar]  ketik pesan…               [model] [kirim]
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
                                    "Model ini tidak support vision.",
                                    Toast.LENGTH_SHORT
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
                    )
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
                // Lebar mengikuti konten, dibatasi max agar tabel/code tetap lega
                .widthIn(max = if (isUser) 320.dp else 400.dp)
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
                Text("Lihat Diff")
            }
        }
    }
}

/**
 * Indikator token minimalis untuk top bar: cincin progres dengan persentase sisa
 * di tengah, dan jumlah sisa token di sebelah kanannya. Tap untuk melihat rincian.
 *
 * - Limit diketahui  → cincin = persen SISA, teks kanan = sisa token.
 * - Limit belum tahu → cincin kosong bertanda "?", teks kanan = token terpakai.
 */
@Composable
private fun TokenRingIndicator(budget: TokenBudgetUi, modifier: Modifier = Modifier) {
    var showDetail by remember { mutableStateOf(false) }

    val knownLimit = budget.hasKnownLimit
    val remainingPct = budget.remainingPercent ?: 0

    val progress by animateFloatAsState(
        targetValue = if (knownLimit) (remainingPct / 100f).coerceIn(0f, 1f) else 0f,
        label = "tokenRingProgress"
    )
    val ringColor by animateColorAsState(
        targetValue = when {
            !knownLimit -> MaterialTheme.colorScheme.outline
            remainingPct <= 10 -> MaterialTheme.colorScheme.error
            remainingPct <= 25 -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.primary
        },
        label = "tokenRingColor"
    )
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val isLow = knownLimit && remainingPct <= 25

    val valueText = when {
        knownLimit -> formatTokenCount(budget.remainingTokens ?: 0)
        budget.usedTokens > 0 -> "~${formatTokenCount(budget.usedTokens)}"
        else -> "—"
    }
    val captionText = if (knownLimit) "sisa" else "terpakai"

    val a11yText = if (knownLimit) {
        "Sisa token $valueText, $remainingPct persen"
    } else {
        "Limit token belum diketahui, terpakai $valueText"
    }

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .clickable { showDetail = true }
                .semantics(mergeDescendants = true) { contentDescription = a11yText }
                .padding(horizontal = 6.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Cincin progres + persentase di tengah
            Box(modifier = Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val stroke = 3.dp.toPx()
                    val arcTopLeft = Offset(stroke / 2f, stroke / 2f)
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    drawArc(
                        color = trackColor,
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = Stroke(width = stroke)
                    )
                    if (progress > 0f) {
                        drawArc(
                            color = ringColor,
                            startAngle = -90f,
                            sweepAngle = 360f * progress,
                            useCenter = false,
                            topLeft = arcTopLeft,
                            size = arcSize,
                            style = Stroke(width = stroke, cap = StrokeCap.Round)
                        )
                    }
                }
                Text(
                    text = if (knownLimit) "$remainingPct" else "?",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 9.sp,
                        lineHeight = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
            }

            Spacer(Modifier.size(6.dp))

            // Sisa token di sebelah kanan cincin
            Column(
                modifier = Modifier.widthIn(min = 30.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 13.sp,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = if (isLow) ringColor else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    text = captionText,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 9.sp,
                        lineHeight = 10.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }

        // Rincian muncul sebagai popup kecil di bawah indikator
        DropdownMenu(
            expanded = showDetail,
            onDismissRequest = { showDetail = false }
        ) {
            Column(
                modifier = Modifier
                    .width(210.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    "Konteks token",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                TokenDetailRow(
                    "Terpakai",
                    if (budget.usedTokens > 0) formatTokenCount(budget.usedTokens) else "—"
                )
                if (knownLimit) {
                    TokenDetailRow("Sisa", "${formatTokenCount(budget.remainingTokens ?: 0)} ($remainingPct%)")
                    TokenDetailRow("Limit", formatTokenCount(budget.maxTokens ?: 0))
                } else {
                    TokenDetailRow("Limit", "Belum diketahui")
                }
                TokenDetailRow(
                    "Sumber",
                    when {
                        budget.fromApi -> "API"
                        budget.usedTokens > 0 -> "Estimasi"
                        else -> "—"
                    }
                )
                if (budget.compacted) {
                    TokenDetailRow("History", "Diringkas")
                }
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
