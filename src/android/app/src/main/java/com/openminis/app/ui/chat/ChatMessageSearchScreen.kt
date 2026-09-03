package com.openminis.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private data class ChatSearchHit(
    val messageId: String,
    val role: String,
    val text: String,
)

/** Full-screen second-level search page scoped to the current conversation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatMessageSearchScreen(
    messages: List<ChatMessage>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val results = remember(messages, query) {
        val needle = query.trim()
        if (needle.isBlank()) emptyList() else messages.mapNotNull { message ->
            val content = if (message.role == "assistant") {
                message.toolBlocks
                    .filter { it.kind == "text" && it.content.isNotBlank() }
                    .joinToString("\n\n") { it.content }
                    .ifBlank { message.content }
            } else message.content
            if (content.contains(needle, ignoreCase = true)) {
                ChatSearchHit(message.id, message.role, searchSnippet(content, needle))
            } else null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("搜索记录") },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        },
                    )
                },
            ) { padding ->
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                        singleLine = true,
                        placeholder = { Text("输入关键词") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = if (query.isNotEmpty()) {
                            {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear")
                                }
                            }
                        } else null,
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(results, key = { it.messageId }) { hit ->
                            val assistant = hit.role == "assistant"
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (assistant) Modifier else Modifier.padding(start = 72.dp)
                                    ),
                                horizontalArrangement = if (assistant) Arrangement.Start else Arrangement.End,
                            ) {
                                Surface(
                                    modifier = Modifier.clickable { onSelect(hit.messageId) },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (assistant) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                    } else {
                                        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.65f)
                                    },
                                ) {
                                    Text(
                                        text = highlighted(hit.text, query.trim()),
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                        maxLines = 3,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

private fun searchSnippet(text: String, query: String): String {
    val index = text.indexOf(query, ignoreCase = true).coerceAtLeast(0)
    val start = (index - 28).coerceAtLeast(0)
    val end = (index + query.length + 72).coerceAtMost(text.length)
    return buildString {
        if (start > 0) append("...")
        append(text.substring(start, end).replace('\n', ' '))
        if (end < text.length) append("...")
    }
}

private fun highlighted(text: String, query: String) = buildAnnotatedString {
    if (query.isBlank()) {
        append(text)
        return@buildAnnotatedString
    }
    var cursor = 0
    while (cursor < text.length) {
        val match = text.indexOf(query, cursor, ignoreCase = true)
        if (match < 0) {
            append(text.substring(cursor))
            break
        }
        append(text.substring(cursor, match))
        withStyle(SpanStyle(color = Color(0xFFE05278), fontWeight = FontWeight.Bold)) {
            append(text.substring(match, match + query.length))
        }
        cursor = match + query.length
    }
}
