package com.kaneki.aicoder.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kaneki.aicoder.data.local.AppPrefs
import com.kaneki.aicoder.data.local.SecurePrefs
import com.kaneki.aicoder.data.local.ZipFileStore
import com.kaneki.aicoder.data.local.db.AppDatabase
import com.kaneki.aicoder.data.remote.AiApiClient
import com.kaneki.aicoder.data.remote.AiApiClientFactory
import com.kaneki.aicoder.data.remote.AiProvider
import com.kaneki.aicoder.data.remote.ImageGenClient
import com.kaneki.aicoder.data.repository.ChatRepository
import com.kaneki.aicoder.domain.engine.ContextBudgetManager
import com.kaneki.aicoder.domain.engine.ToolCallingLoop
import com.kaneki.aicoder.domain.model.ChatTurn
import com.kaneki.aicoder.domain.model.FileTree
import com.kaneki.aicoder.domain.model.PendingChange
import com.kaneki.aicoder.domain.android.AndroidProjectAnalyzer
import com.kaneki.aicoder.domain.tools.ToolExecutor
import com.kaneki.aicoder.domain.zip.ProjectTreeLoader
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import com.kaneki.aicoder.domain.util.FormatUtils

sealed interface ChatUiState {
    data object Idle : ChatUiState
    data object Loading : ChatUiState
    data class Error(val message: String) : ChatUiState
}

/**
 * [maxTokens] null berarti context limit model aktif BELUM DIKETAHUI (provider
 * tidak mengembalikan info context length). Tidak ada lagi fallback diam-diam
 * ke angka statis (900rb) — kalau null, UI (lihat [TokenRingIndicator] di ChatScreen) wajib
 * menampilkan status "belum ada info", bukan angka yang terkesan aktual
 * padahal cuma tebakan.
 */
data class TokenBudgetUi(
    val usedTokens: Int = 0,
    val maxTokens: Int? = null,
    val percent: Int? = null,
    val fromApi: Boolean = false,
    val compacted: Boolean = false
) {
    /** True kalau limit context model aktif diketahui, jadi progress bar/persentase bisa ditampilkan. */
    val hasKnownLimit: Boolean get() = maxTokens != null

    /** Sisa token sampai limit konteks habis — null kalau limit belum diketahui. */
    val remainingTokens: Int?
        get() = maxTokens?.let { (it - usedTokens).coerceAtLeast(0) }

    /** Persentase sisa (100 = penuh, 0 = habis) — null kalau limit belum diketahui. */
    val remainingPercent: Int?
        get() = maxTokens?.let { max ->
            if (max <= 0) null
            else ((remainingTokens ?: 0).toDouble() / max * 100).toInt().coerceIn(0, 100)
        }
}

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val securePrefs = SecurePrefs(application)
    private val appPrefs = AppPrefs.getInstance(application)
    private val zipFileStore = ZipFileStore(application)
    // Limit context window dibuat dari model yang TERSIMPAN untuk provider aktif
    // (diisi ModelPickerSheet dari API tiap provider), bukan angka tetap — kalau
    // belum ada info tersimpan (mis. install baru / belum pernah pilih model /
    // provider tidak mengembalikan context length), tetap NULL, bukan jatuh ke
    // fallback statis yang terkesan seperti data aktual.
    private val budgetManager = ContextBudgetManager(
        maxContextTokens = appPrefs.getSelectedModelContextLimit()
    )
    private val chatRepository = ChatRepository(
        AppDatabase.getInstance(application).chatDao()
    )

    /** Client khusus text-to-image (Cloudflare Workers AI / Gemini Imagen). */
    private val imageGenClient = ImageGenClient()

    init {
        // Migrasi key Gemini lama (sebelum dukungan multi-provider) sekali di awal,
        // supaya user existing tidak kehilangan API key yang sudah divalidasi.
        securePrefs.migrateLegacyGeminiKeyIfNeeded()
    }

    /** Provider aktif (Gemini/Groq/OpenRouter); diubah lewat pemilih model di ChatScreen. */
    val selectedProvider: StateFlow<AiProvider> = appPrefs.selectedProvider

    /** Model aktif UNTUK provider aktif saat ini. */
    val selectedModel: StateFlow<String> = appPrefs.selectedModel

    /** Nama tampilan model aktif (didapat saat fetch live di ModelPickerSheet). */
    val selectedModelLabel: StateFlow<String> = appPrefs.selectedModelLabel

    /**
     * Client aktif, dibuat ulang tiap provider berganti (masing-masing provider
     * beda base URL/format request, lihat [AiApiClientFactory]). `close()`
     * client lama dipanggil dulu supaya HttpClient sebelumnya tidak bocor.
     */
    private var apiClient: AiApiClient = AiApiClientFactory.create(appPrefs.getSelectedProvider())

    /**
     * Ganti provider + model aktif sekaligus (dipanggil dari [ModelPickerSheet]).
     *
     * [contextLimit] adalah context window ASLI model ini (dari katalog live
     * provider, lihat [com.kaneki.aicoder.data.remote.LiveModel.contextLimit]).
     * Dipakai untuk update [budgetManager] SEKARANG JUGA (bukan menunggu
     * request API berikutnya), supaya begin ganti model indikator token
     * langsung menampilkan limit yang benar — sebelumnya limit selalu 900rb
     * untuk semua model/provider, tidak "aktual" dan tidak "adaptif" per model.
     */
    fun setSelectedProviderAndModel(
        provider: AiProvider,
        modelId: String,
        label: String = modelId,
        contextLimit: Int? = null,
        supportsVision: Boolean = false,
        supportsImageGen: Boolean = false
    ) {
        if (appPrefs.getSelectedProvider() != provider) {
            apiClient.close()
            apiClient = AiApiClientFactory.create(provider)
            appPrefs.setSelectedProvider(provider)
        }
        appPrefs.setSelectedModel(modelId, label, contextLimit, supportsVision, supportsImageGen)
        budgetManager.updateMaxTokens(contextLimit)
        // Reset tampilan ke state "belum ada pemakaian" TAPI dengan limit model
        // baru — bukan TokenBudgetUi() polos yang balik ke default 900rb lama.
        _tokenBudget.value = TokenBudgetUi(maxTokens = budgetManager.maxContextTokens)
    }

    /** True jika model aktif diperkirakan mendukung input gambar. */
    val selectedModelSupportsVision: StateFlow<Boolean> = appPrefs.selectedModelSupportsVision

    /** True jika model aktif adalah model text-to-image (alur ImageGenClient, bukan chat). */
    val selectedModelSupportsImageGen: StateFlow<Boolean> = appPrefs.selectedModelSupportsImageGen

    /** True jika provider aktif saat ini sudah punya API key tersimpan. */
    fun hasApiKeyForActiveProvider(): Boolean = securePrefs.hasApiKey(appPrefs.getSelectedProvider())

    /** Dipakai [ModelPickerSheet] untuk fetch katalog model live tiap provider. */
    fun getApiKeyFor(provider: AiProvider): String? = securePrefs.getApiKey(provider)

    private var projectId: String? = null
    private var fileTree: FileTree = FileTree.EMPTY
    private var projectDir: File? = null
    private var sendJob: Job? = null

    private val history = mutableListOf<ChatTurn>()
    private val pendingMap = mutableMapOf<String, String>()

    /** Path baru -> path lama, untuk rename_file yang masih di staging (belum di-apply). */
    private val renamesMap = mutableMapOf<String, String>()

    private val _messages = MutableStateFlow<List<ChatDisplayItem>>(emptyList())
    val messages: StateFlow<List<ChatDisplayItem>> = _messages.asStateFlow()

    private val _uiState = MutableStateFlow<ChatUiState>(ChatUiState.Idle)
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _toolActivity = MutableStateFlow<String?>(null)
    val toolActivity: StateFlow<String?> = _toolActivity.asStateFlow()

    private val _pendingChanges = MutableStateFlow<List<PendingChange>>(emptyList())
    val pendingChanges: StateFlow<List<PendingChange>> = _pendingChanges.asStateFlow()

    private val _tokenBudget = MutableStateFlow(TokenBudgetUi(maxTokens = budgetManager.maxContextTokens))
    val tokenBudget: StateFlow<TokenBudgetUi> = _tokenBudget.asStateFlow()

    /** Teks yang sedang di-stream dari model (kosong saat idle). */
    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    /** Hasil deteksi project Android (null jika bukan Android / belum dianalisis). */
    private val _androidInfo = MutableStateFlow<AndroidProjectInfo?>(null)
    val androidInfo: StateFlow<AndroidProjectInfo?> = _androidInfo.asStateFlow()

    private var cachedAndroidBlock: String = ""

    /**
     * projectId null = "chat bebas" (belum ada project di-import). Riwayat chat mode ini
     * disimpan di bawah key konstan [FREE_CHAT_ID] agar tetap persisten tanpa project asli,
     * dan tool file (read/write/search) berjalan atas [FileTree.EMPTY] sehingga model akan
     * menjawab bahwa belum ada project ketika mencoba mengakses file.
     */
    fun initProject(projectId: String?, fileTree: FileTree) {
        val effectiveId = projectId ?: FREE_CHAT_ID
        val resolvedDir = if (projectId != null) {
            zipFileStore.projectDir(projectId)
        } else {
            zipFileStore.freeChatDir()
        }
        if (this.projectId == effectiveId && _messages.value.isNotEmpty()) {
            this.fileTree = fileTree
            this.projectDir = resolvedDir
            // Refresh analisis Android jika fileTree berubah (mis. setelah apply)
            if (projectId != null) {
                val analysis = AndroidProjectAnalyzer.analyze(fileTree, resolvedDir)
                if (analysis.isAndroidProject) {
                    cachedAndroidBlock = analysis.summaryBlock
                    _androidInfo.value = AndroidProjectInfo(
                        score = analysis.completenessScore,
                        issues = analysis.issues,
                        stringsXmlPaths = analysis.keyPaths.stringsXml,
                        manifestPath = analysis.keyPaths.manifest,
                        appNameHints = analysis.hints.filter {
                            it.startsWith("app_name") || it.startsWith("android:label")
                        }
                    )
                } else {
                    cachedAndroidBlock = ""
                    _androidInfo.value = null
                }
            }
            return
        }
        this.projectId = effectiveId
        this.fileTree = fileTree
        this.projectDir = resolvedDir
        pendingMap.clear()
        renamesMap.clear()
        _pendingChanges.value = emptyList()
        _tokenBudget.value = TokenBudgetUi(maxTokens = budgetManager.maxContextTokens)
        _uiState.value = ChatUiState.Idle
        cachedAndroidBlock = ""
        _androidInfo.value = null

        // Analisis struktur Android (path + baca app_name dari disk jika ada)
        if (projectId != null) {
            val analysis = AndroidProjectAnalyzer.analyze(fileTree, resolvedDir)
            if (analysis.isAndroidProject) {
                cachedAndroidBlock = analysis.summaryBlock
                _androidInfo.value = AndroidProjectInfo(
                    score = analysis.completenessScore,
                    issues = analysis.issues,
                    stringsXmlPaths = analysis.keyPaths.stringsXml,
                    manifestPath = analysis.keyPaths.manifest,
                    appNameHints = analysis.hints.filter { it.startsWith("app_name") || it.startsWith("android:label") }
                )
            }
        }

        viewModelScope.launch {
            val display = chatRepository.loadDisplayMessages(effectiveId)
            val turns = chatRepository.loadChatTurns(effectiveId)
            history.clear()
            history.addAll(turns)
            _messages.value = display
        }
    }

    /** True jika sedang di mode chat bebas (tanpa project di-import). */
    val hasProject: Boolean
        get() = projectId != null && projectId != FREE_CHAT_ID

    companion object {
        /** Key riwayat chat untuk mode "chat bebas" (tanpa project). */
        const val FREE_CHAT_ID = "__free_chat__"
    }

    fun sendMessage(
        text: String,
        images: List<com.kaneki.aicoder.domain.model.ImageAttachment> = emptyList()
    ) {
        val trimmed = text.trim()
        if ((trimmed.isEmpty() && images.isEmpty()) || _uiState.value == ChatUiState.Loading) return

        val activeProvider = appPrefs.getSelectedProvider()
        val apiKey = securePrefs.getApiKey(activeProvider)
        if (apiKey.isNullOrBlank()) {
            _uiState.value = ChatUiState.Error(
                "API key ${activeProvider.displayName} belum tersimpan. Buka Pengaturan."
            )
            return
        }
        if (appPrefs.getSelectedModel().isBlank()) {
            // Khusus provider yang tidak punya default model aman tanpa fetch live
            // (mis. OpenRouter, karena model gratisnya berubah-ubah), user WAJIB
            // memilih model dulu lewat ModelPickerSheet sebelum bisa kirim pesan.
            _uiState.value = ChatUiState.Error(
                "Pilih model ${activeProvider.displayName} dulu lewat ikon model di atas."
            )
            return
        }
        if (images.isNotEmpty() && !appPrefs.getSelectedModelSupportsVision()) {
            _uiState.value = ChatUiState.Error(
                "Model aktif tidak mendukung vision/gambar. Pilih model bertanda Vision di pemilih model."
            )
            return
        }

        // Model text-to-image → alur ImageGenClient (bukan tool-calling chat)
        if (appPrefs.getSelectedModelSupportsImageGen()) {
            if (trimmed.isEmpty()) {
                _uiState.value = ChatUiState.Error(
                    "Tulis prompt untuk generate gambar (model 🖼 Gambar aktif)."
                )
                return
            }
            if (images.isNotEmpty()) {
                _uiState.value = ChatUiState.Error(
                    "Model generate gambar tidak menerima lampiran foto. Hapus foto atau pilih model chat/vision."
                )
                return
            }
            runImageGeneration(apiKey, activeProvider, trimmed)
            return
        }
        val dir = projectDir
        val pid = projectId
        if (dir == null || !dir.exists() || pid == null) {
            _uiState.value = ChatUiState.Error("Project belum dimuat. Import ZIP dulu.")
            return
        }

        val displayText = when {
            trimmed.isNotEmpty() -> trimmed
            images.size == 1 -> "[1 gambar]"
            else -> "[${images.size} gambar]"
        }
        val imageUris = images.mapNotNull { it.localUri }

        _uiState.value = ChatUiState.Loading
        _toolActivity.value = null
        _messages.value = _messages.value + ChatDisplayItem.UserText(displayText, imageUris)

        sendJob?.cancel()
        sendJob = viewModelScope.launch {
            val persistText = if (images.isEmpty()) trimmed
            else (if (trimmed.isNotEmpty()) "$trimmed " else "") + "[${images.size} gambar dilampirkan]"
            chatRepository.appendUser(pid, persistText)

            val executor = ToolExecutor(
                projectDir = dir,
                fileTree = fileTree,
                pendingChanges = pendingMap,
                renames = renamesMap
            )
            val loop = ToolCallingLoop(
                apiClient = apiClient,
                toolExecutor = executor,
                contextBudget = budgetManager
            )
            val summary = buildFileTreeSummary(fileTree)

            try {
                _streamingText.value = ""
                val outcome = loop.run(
                    apiKey = apiKey,
                    model = appPrefs.getSelectedModel(),
                    userMessage = if (trimmed.isNotEmpty()) trimmed else "Tolong analisis gambar yang dilampirkan.",
                    history = history,
                    fileTreeSummary = summary,
                    hasProject = hasProject,
                    androidBlock = cachedAndroidBlock,
                    images = images,
                    onToolActivity = { activity ->
                        if (isActive) _toolActivity.value = activity
                    },
                    onTextChunk = { chunk ->
                        if (isActive) {
                            _streamingText.value = _streamingText.value + chunk
                        }
                    },
                    onBudgetUpdate = { snapshot, compacted ->
                        if (!isActive) return@run
                        _tokenBudget.value = TokenBudgetUi(
                            usedTokens = snapshot.displayUsed,
                            maxTokens = snapshot.maxTokens,
                            percent = snapshot.percent,
                            fromApi = snapshot.apiTotalTokens != null,
                            compacted = compacted || _tokenBudget.value.compacted
                        )
                    }
                )

                if (!isActive) return@launch

                _toolActivity.value = null
                _streamingText.value = ""

                if (outcome.lastApiTotalTokens != null) {
                    // Pakai usageSummary sebagai satu-satunya sumber hitung persen/maxTokens
                    // (bukan hitung ulang manual di sini) supaya tidak ada dua rumus yang
                    // bisa saling berbeda kalau salah satu lupa di-update.
                    val snapshot = budgetManager.usageSummary(history, outcome.lastApiTotalTokens)
                    _tokenBudget.value = _tokenBudget.value.copy(
                        usedTokens = snapshot.displayUsed,
                        maxTokens = snapshot.maxTokens,
                        fromApi = true,
                        percent = snapshot.percent,
                        compacted = outcome.historyCompacted || _tokenBudget.value.compacted
                    )
                }

                if (outcome.errorMessage != null) {
                    _uiState.value = ChatUiState.Error(outcome.errorMessage)
                    if (outcome.finalText.isNotBlank()) {
                        _messages.value =
                            _messages.value + ChatDisplayItem.AssistantText(outcome.finalText)
                        chatRepository.appendAssistant(pid, outcome.finalText)
                    }
                } else {
                    var reply = outcome.finalText
                    if (outcome.historyCompacted) {
                        reply = (if (reply.isNotBlank()) "$reply\n\n" else "") +
                            "ℹ️ History chat diringkas otomatis karena mendekati batas konteks."
                    }
                    if (reply.isNotBlank()) {
                        _messages.value = _messages.value + ChatDisplayItem.AssistantText(reply)
                        chatRepository.appendAssistant(pid, reply)
                    } else if (outcome.pendingChanges.isNotEmpty()) {
                        val auto =
                            "Selesai. ${outcome.pendingChanges.size} file disiapkan di staging."
                        _messages.value = _messages.value + ChatDisplayItem.AssistantText(auto)
                        chatRepository.appendAssistant(pid, auto)
                    }
                    _pendingChanges.value = outcome.pendingChanges
                    _uiState.value = ChatUiState.Idle
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _toolActivity.value = null
                _uiState.value = ChatUiState.Idle
                _messages.value = _messages.value + ChatDisplayItem.AssistantText(
                    "⏹️ Dibatalkan."
                )
                throw e
            } catch (e: Exception) {
                _toolActivity.value = null
                _uiState.value = ChatUiState.Error(e.message ?: "Error tidak diketahui")
            }
        }
    }


    private fun runImageGeneration(apiKey: String, provider: AiProvider, prompt: String) {
        // projectId null = chat bebas -> pakai key konstan FREE_CHAT_ID (sama seperti initProject)
        val pid = projectId ?: FREE_CHAT_ID
        _uiState.value = ChatUiState.Loading
        _toolActivity.value = "Menghasilkan gambar…"
        _streamingText.value = ""
        _messages.value = _messages.value + ChatDisplayItem.UserText(prompt)

        sendJob?.cancel()
        sendJob = viewModelScope.launch {
            chatRepository.appendUser(pid, "[generate gambar] $prompt")
            try {
                val result = imageGenClient.generate(
                    provider = provider,
                    apiKey = apiKey,
                    model = appPrefs.getSelectedModel(),
                    prompt = prompt
                )
                if (!isActive) return@launch
                when (result) {
                    is com.kaneki.aicoder.data.remote.ImageGenResult.Success -> {
                        val path = saveGeneratedImage(result.base64, result.mimeType)
                        if (path != null) {
                            _messages.value = _messages.value + ChatDisplayItem.AssistantImage(
                                localPath = path,
                                caption = "Hasil generate: $prompt"
                            )
                            chatRepository.appendAssistant(
                                pid,
                                "[gambar dihasilkan] $prompt → $path"
                            )
                            _uiState.value = ChatUiState.Idle
                        } else {
                            _uiState.value = ChatUiState.Error("Gagal menyimpan file gambar hasil generate")
                        }
                    }
                    is com.kaneki.aicoder.data.remote.ImageGenResult.Error -> {
                        // Cukup SATU tempat tampil: banner error (uiState). Sebelumnya pesan
                        // yang sama juga ditambahkan sebagai bubble chat sehingga muncul dobel.
                        _uiState.value = ChatUiState.Error(
                            "Gagal generate gambar: ${result.message}"
                        )
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    _uiState.value = ChatUiState.Error(e.message ?: "Gagal generate gambar")
                }
            } finally {
                if (isActive) {
                    _toolActivity.value = null
                    if (_uiState.value is ChatUiState.Loading) {
                        _uiState.value = ChatUiState.Idle
                    }
                }
            }
        }
    }

    private fun saveGeneratedImage(base64: String, mimeType: String): String? {
        return try {
            val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
            val ext = when {
                "jpeg" in mimeType || "jpg" in mimeType -> "jpg"
                "webp" in mimeType -> "webp"
                else -> "png"
            }
            val dir = java.io.File(getApplication<android.app.Application>().filesDir, "generated_images")
            if (!dir.exists()) dir.mkdirs()
            val file = java.io.File(dir, "img_${System.currentTimeMillis()}.$ext")
            file.writeBytes(bytes)
            file.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    fun cancelGeneration() {
        sendJob?.cancel()
        sendJob = null
        _toolActivity.value = null
        _streamingText.value = ""
        _uiState.value = ChatUiState.Idle
    }

    fun clearPending() {
        pendingMap.clear()
        renamesMap.clear()
        _pendingChanges.value = emptyList()
    }

    /**
     * Muat ulang [FileTree] dari disk dan sinkronkan ke [initProject] (jalur
     * refresh, bukan reset — chat & pendingMap tidak disentuh karena projectId
     * sama). Dipanggil setelah user apply perubahan di layar Diff, supaya
     * list_directory/read_file di giliran chat berikutnya melihat struktur
     * project TERBARU (termasuk hasil rename_file: path lama sudah hilang,
     * path baru sudah ada) alih-alih snapshot lama sebelum apply.
     */
    fun refreshFileTree() {
        val dir = projectDir ?: return
        if (!hasProject) return
        val pid = projectId ?: return
        viewModelScope.launch {
            val freshTree = ProjectTreeLoader.load(dir)
            initProject(pid, freshTree)
        }
    }

    fun clearChat() {
        val pid = projectId
        cancelGeneration()
        history.clear()
        pendingMap.clear()
        renamesMap.clear()
        _messages.value = emptyList()
        _pendingChanges.value = emptyList()
        _toolActivity.value = null
        // History dikosongkan → pemakaian token kembali ke 0, TAPI limit maksimalnya
        // tetap ikut model/provider yang sedang aktif (bukan balik ke 900rb tetap
        // untuk semua model). "Sisa token" jadi penuh lagi karena memang belum ada
        // pemakaian baru — itu perilaku yang benar, bukan bug.
        _tokenBudget.value = TokenBudgetUi(maxTokens = budgetManager.maxContextTokens)
        _uiState.value = ChatUiState.Idle
        if (pid != null) {
            viewModelScope.launch {
                chatRepository.clearProject(pid)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        sendJob?.cancel()
        apiClient.close()
        imageGenClient.close()
    }

    private fun buildFileTreeSummary(tree: FileTree): String {
        if (tree.files.isEmpty()) return "(project kosong)"
        val textFiles = tree.files.filter { !it.isBinary }.sortedBy { it.path }
        val binaryCount = tree.files.count { it.isBinary }
        val limit = 150
        val listed = textFiles.take(limit).joinToString("\n") { f ->
            val size = when {
                f.sizeBytes < 1024 -> "${f.sizeBytes} B"
                f.sizeBytes < 1024 * 1024 -> "%.1f KB".format(f.sizeBytes / 1024.0)
                else -> "%.1f MB".format(f.sizeBytes / (1024.0 * 1024.0))
            }
            "${f.path} ($size)"
        }
        val more = (textFiles.size - limit).coerceAtLeast(0)
        return buildString {
            append("Total: ${tree.totalFileCount} file")
            append(" (${textFiles.size} teks, $binaryCount biner)")
            append(" · ${FormatUtils.formatByteSize(tree.totalSizeBytes)}\n")
            append(listed)
            if (more > 0) append("\n... dan $more file teks lainnya (gunakan list_directory / search_code)")
            if (binaryCount > 0) append("\n(file biner tidak dikirim isinya ke model)")
        }
    }

}

sealed interface ChatDisplayItem {
    data class UserText(
        val text: String,
        /** URI lokal gambar untuk ditampilkan di bubble (opsional). */
        val imageUris: List<String> = emptyList()
    ) : ChatDisplayItem
    data class AssistantText(val text: String) : ChatDisplayItem
    /** Gambar hasil generate (file di cache app) + caption prompt-nya. */
    data class AssistantImage(
        val localPath: String,
        val caption: String = ""
    ) : ChatDisplayItem
}

/** Info ringkas project Android untuk UI (badge / subtitle di Chat). */
data class AndroidProjectInfo(
    val score: Int,
    val issues: List<String>,
    val stringsXmlPaths: List<String>,
    val manifestPath: String?,
    val appNameHints: List<String>
)

