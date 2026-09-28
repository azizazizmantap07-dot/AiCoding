package com.kaneki.aicoder.ui.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kaneki.aicoder.data.remote.AiProvider
import com.kaneki.aicoder.data.remote.LiveModel
import com.kaneki.aicoder.data.remote.ModelPurpose
import com.kaneki.aicoder.data.remote.ModelCatalogResult
import com.kaneki.aicoder.data.remote.ModelCatalogService

/**
 * Dialog pemilih provider + model. Daftar model TIDAK di-hardcode — begitu
 * provider (atau tab) dipilih, [ModelCatalogService] fetch langsung dari API
 * provider itu dan filter yang gratis (skema filter beda per provider, lihat
 * dokumentasi di [ModelCatalogService]). Ini sengaja dibuat adaptif karena
 * daftar model gratis, terutama `:free` di OpenRouter, sering berubah/pensiun.
 *
 * [apiKeyForProvider] dipakai untuk fetch — kalau provider belum punya key
 * tersimpan, tab itu menampilkan pesan "isi API key dulu" alih-alih error.
 */
@Composable
fun ModelPickerSheet(
    currentProvider: AiProvider,
    currentModel: String,
    apiKeyForProvider: (AiProvider) -> String?,
    onDismiss: () -> Unit,
    onSelect: (provider: AiProvider, modelId: String, label: String, contextLimit: Int?, supportsVision: Boolean, supportsImageGen: Boolean) -> Unit
) {
    var selectedProvider by remember { mutableStateOf(currentProvider) }
    var selectedModelId by remember(selectedProvider) {
        mutableStateOf(if (selectedProvider == currentProvider) currentModel else "")
    }
    var customText by remember(selectedProvider) { mutableStateOf("") }
    var useCustom by remember(selectedProvider) { mutableStateOf(false) }

    var catalogState by remember(selectedProvider) {
        mutableStateOf<ModelCatalogResult?>(null)
    }
    var isLoading by remember(selectedProvider) { mutableStateOf(false) }
    var refreshTick by remember(selectedProvider) { mutableStateOf(0) }
    /** null = semua kategori relevan; selain itu filter chip Chat/Coding/Vision. */
    var purposeFilter by remember(selectedProvider) { mutableStateOf<ModelPurpose?>(null) }

    val apiKey = apiKeyForProvider(selectedProvider)

    LaunchedEffect(selectedProvider, refreshTick) {
        val key = apiKey
        if (key.isNullOrBlank()) {
            catalogState = ModelCatalogResult.Error(
                "API key ${selectedProvider.displayName} belum diisi. Buka Pengaturan dulu."
            )
            return@LaunchedEffect
        }
        isLoading = true
        catalogState = ModelCatalogService.fetchFreeModels(
            selectedProvider, key, forceRefresh = refreshTick > 0
        )
        isLoading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pilih Provider & Model") },
        text = {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AiProvider.entries.forEach { provider ->
                        FilterChip(
                            selected = selectedProvider == provider,
                            onClick = { selectedProvider = provider },
                            label = { Text(provider.displayName) }
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        selectedProvider.freeTierNote,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    if (!apiKey.isNullOrBlank()) {
                        IconButton(onClick = {
                            ModelCatalogService.invalidate(selectedProvider, apiKey)
                            refreshTick++
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Muat ulang daftar model")
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                when (val state = catalogState) {
                    null -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isLoading) CircularProgressIndicator()
                    }
                    is ModelCatalogResult.Error -> {
                        Text(
                            state.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    is ModelCatalogResult.Success -> {
                        Text(
                            "Hanya model untuk chat, coding/edit file, dan vision (maks. 24).",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = purposeFilter == null,
                                onClick = { purposeFilter = null },
                                label = { Text("Semua") }
                            )
                            ModelPurpose.SUPPORTED_IN_APP.forEach { purpose ->
                                val count = state.models.count { purpose in it.purposes }
                                if (count > 0) {
                                    FilterChip(
                                        selected = purposeFilter == purpose,
                                        onClick = { purposeFilter = purpose },
                                        label = { Text("${purpose.emoji} ${purpose.label} ($count)") }
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        val visible = if (purposeFilter == null) {
                            state.models
                        } else {
                            state.models.filter { purposeFilter in it.purposes }
                        }
                        if (visible.isEmpty()) {
                            Text(
                                "Tidak ada model di kategori ini.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                                items(visible, key = { it.id }) { model ->
                                    ModelRow(
                                        model = model,
                                        selected = !useCustom && selectedModelId == model.id,
                                        onClick = {
                                            useCustom = false
                                            selectedModelId = model.id
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = useCustom,
                            onClick = { useCustom = true },
                            role = Role.RadioButton
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = useCustom, onClick = null)
                    Text(
                        "Custom (isi manual)",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }

                if (useCustom) {
                    OutlinedTextField(
                        value = customText,
                        onValueChange = { customText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Model ID") },
                        placeholder = { Text("mis. llama-3.2-90b-vision-preview") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Model custom mungkin BERBAYAR — pastikan kamu tahu model ini gratis " +
                            "di akun ${selectedProvider.displayName} kamu sebelum memakainya.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val finalId = if (useCustom) customText.trim() else selectedModelId
                    val matchedModel = (catalogState as? ModelCatalogResult.Success)
                        ?.models?.firstOrNull { it.id == finalId }
                    val label = if (useCustom) finalId else (matchedModel?.label ?: finalId)
                    // Model custom (diisi manual) tidak punya info context window dari
                    // katalog live, jadi contextLimit null -> ChatViewModel jatuh ke fallback.
                    val contextLimit = if (useCustom) null else matchedModel?.contextLimit
                    val supportsVision = if (useCustom) {
                        com.kaneki.aicoder.data.remote.VisionCapability.guess(finalId)
                    } else {
                        matchedModel?.supportsVision == true
                    }
                    val supportsImageGen = if (useCustom) {
                        com.kaneki.aicoder.data.remote.ModelRelevance.isImageGen(finalId)
                    } else {
                        matchedModel?.supportsImageGen == true ||
                            ModelPurpose.IMAGE_GEN in (matchedModel?.purposes ?: emptySet())
                    }
                    if (finalId.isNotBlank()) onSelect(
                        selectedProvider, finalId, label, contextLimit, supportsVision, supportsImageGen
                    )
                },
                enabled = if (useCustom) customText.isNotBlank() else selectedModelId.isNotBlank()
            ) {
                Text("Pilih")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal")
            }
        }
    )
}

@Composable
private fun ModelRow(
    model: LiveModel,
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
        Column(Modifier.padding(start = 8.dp)) {
            Text(model.label, style = MaterialTheme.typography.bodyLarge)
            val purposeTags = model.purposes
                .filter { it in ModelPurpose.SUPPORTED_IN_APP }
                .joinToString(" ") { "${it.emoji}${it.label}" }
            val sub = buildString {
                append("GRATIS")
                if (purposeTags.isNotBlank()) append(" · $purposeTags")
                model.contextLimit?.let { append(" · ${it / 1000}K") }
            }
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            if (!model.description.isNullOrBlank() && model.description !in sub) {
                Text(
                    model.description.take(80),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
