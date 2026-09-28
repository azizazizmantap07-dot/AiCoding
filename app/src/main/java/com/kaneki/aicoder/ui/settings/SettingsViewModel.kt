package com.kaneki.aicoder.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kaneki.aicoder.data.local.AppPrefs
import com.kaneki.aicoder.data.local.SecurePrefs
import com.kaneki.aicoder.data.local.ThemeMode
import com.kaneki.aicoder.data.remote.AiProvider
import com.kaneki.aicoder.data.remote.ApiKeyValidationResult
import com.kaneki.aicoder.data.repository.ApiKeyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** State khusus layar setup/ganti API key. */
sealed interface ApiKeySetupUiState {
    data object Idle : ApiKeySetupUiState
    data object Validating : ApiKeySetupUiState
    data object Saved : ApiKeySetupUiState
    data class Error(val message: String) : ApiKeySetupUiState
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val appPrefs = AppPrefs.getInstance(application)
    private val securePrefs = SecurePrefs(application)
    private val apiKeyRepository = ApiKeyRepository(securePrefs = securePrefs)

    val themeMode: StateFlow<ThemeMode> = appPrefs.themeMode

    /**
     * Provider yang sedang diedit di layar setup key — default ke provider
     * aktif chat, tapi user boleh pindah tab provider di layar ini untuk
     * menambahkan/mengganti key provider lain tanpa mengubah provider aktif
     * sampai submit berhasil (lihat [submitKey]).
     */
    private val _editingProvider = MutableStateFlow(appPrefs.getSelectedProvider())
    val editingProvider: StateFlow<AiProvider> = _editingProvider.asStateFlow()

    fun setEditingProvider(provider: AiProvider) {
        _editingProvider.value = provider
        _uiState.value = ApiKeySetupUiState.Idle
        refreshKeyState()
    }

    private val _hasApiKey = MutableStateFlow(securePrefs.hasApiKey(_editingProvider.value))
    val hasApiKey: StateFlow<Boolean> = _hasApiKey.asStateFlow()

    /** True jika provider MANAPUN sudah punya key — dipakai gate "Kembali" di SettingsScreen. */
    val hasAnyApiKey: Boolean
        get() = securePrefs.hasAnyApiKey()

    private val _uiState = MutableStateFlow<ApiKeySetupUiState>(ApiKeySetupUiState.Idle)
    val uiState: StateFlow<ApiKeySetupUiState> = _uiState.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        appPrefs.setThemeMode(mode)
    }

    fun clearApiKey() {
        apiKeyRepository.clearKey(_editingProvider.value)
        _hasApiKey.value = false
        _uiState.value = ApiKeySetupUiState.Idle
    }

    fun refreshKeyState() {
        _hasApiKey.value = securePrefs.hasApiKey(_editingProvider.value)
    }

    fun submitKey(rawKey: String) {
        val key = rawKey.trim()
        if (key.isEmpty()) {
            _uiState.value = ApiKeySetupUiState.Error("API key tidak boleh kosong.")
            return
        }
        val provider = _editingProvider.value
        _uiState.value = ApiKeySetupUiState.Validating
        viewModelScope.launch {
            when (val result = apiKeyRepository.validateAndSave(provider, key)) {
                is ApiKeyValidationResult.Valid -> {
                    _hasApiKey.value = true
                    _uiState.value = ApiKeySetupUiState.Saved
                    // Provider yang baru diisi key-nya otomatis jadi provider aktif,
                    // supaya user langsung bisa chat tanpa buka ModelPickerSheet lagi.
                    appPrefs.setSelectedProvider(provider)
                }
                is ApiKeyValidationResult.Invalid -> {
                    _uiState.value = ApiKeySetupUiState.Error(
                        result.reason.ifBlank { "API key tidak valid." }
                    )
                }
                is ApiKeyValidationResult.NetworkError -> {
                    _uiState.value = ApiKeySetupUiState.Error(
                        "Koneksi gagal: ${result.reason}"
                    )
                }
            }
        }
    }
}
