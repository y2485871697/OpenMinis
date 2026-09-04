package com.openminis.app.ui.chat

/**
 * Normalize only escaped newlines that clearly belong to a Markdown table.
 * Some gateways/models serialize the assistant text as `| a | b |\\n|---|`;
 * treating every literal `\\n` as a newline would corrupt ordinary prose,
 * JSON and code blocks, so the conversion is gated by table syntax.
 */
internal fun normalizeMarkdownTableEscapes(text: String): String {
    if (!text.contains("\\n") || !text.contains('|')) return text
    val normalized = text.replace("\\r\\n", "\\n")
    val lines = normalized.split("\\n")
    val separator = lines.indexOfFirst { it.trim().matches(MARKDOWN_TABLE_SEPARATOR) }
    if (separator <= 0 || !lines[separator - 1].contains('|')) return text
    return normalized.replace("\\n", "\n")
}

private val MARKDOWN_TABLE_SEPARATOR = Regex(
    "^\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|?$",
)
