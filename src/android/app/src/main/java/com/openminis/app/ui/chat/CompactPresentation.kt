package com.openminis.app.ui.chat

internal fun compactModelDisplayLabel(
    modelName: String,
    modelId: String,
    providerLabel: String,
    providerType: String,
): String {
    fun clean(value: String) = value.replace('\r', ' ').replace('\n', ' ').trim()
    val model = clean(modelName).ifEmpty { clean(modelId) }
    val provider = clean(providerLabel).ifEmpty { clean(providerType) }
    return if (provider.isEmpty()) model else "$model ($provider)"
}
