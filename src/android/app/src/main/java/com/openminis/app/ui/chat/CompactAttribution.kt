package com.openminis.app.ui.chat

// Existing markers store one or more "model (provider)" labels, joined outside parentheses.
// Keep that persisted format compatible; only the overview subtitle changes presentation.
internal fun compactAttributionSubtitle(storedLabel: String): String {
    val labels = mutableListOf<String>()
    var depth = 0
    var start = 0
    for (index in storedLabel.indices) {
        when (storedLabel[index]) {
            '(' -> depth++
            ')' -> depth = (depth - 1).coerceAtLeast(0)
        }
        if (index >= start && depth == 0 && storedLabel.startsWith(" / ", index)) {
            labels.add(storedLabel.substring(start, index))
            start = index + 3
        }
    }
    labels.add(storedLabel.substring(start))
    return labels.map { label ->
        val value = label.trim()
        var nested = 0
        var providerStart = -1
        if (value.endsWith(')')) {
            for (index in value.lastIndex downTo 0) {
                when (value[index]) {
                    ')' -> nested++
                    '(' -> {
                        nested--
                        if (nested == 0) { providerStart = index; break }
                    }
                }
            }
        }
        if (providerStart > 0 && value[providerStart - 1] == ' ') {
            val model = value.substring(0, providerStart).trim()
            val provider = value.substring(providerStart + 1, value.lastIndex).trim()
            if (provider.isNotEmpty()) "$provider\u00b7$model" else model
        } else value
    }.filter { it.isNotEmpty() }.joinToString(" / ")
}
