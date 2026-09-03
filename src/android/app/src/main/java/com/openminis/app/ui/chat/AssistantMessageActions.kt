package com.openminis.app.ui.chat

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R

private data class TranslationLanguageOption(
    val tag: String,
    val displayName: String,
)

private val translationLanguageOptions = listOf(
    TranslationLanguageOption("zh-Hans", "简体中文"),
    TranslationLanguageOption("en", "English"),
    TranslationLanguageOption("zh-Hant", "繁體中文"),
    TranslationLanguageOption("ja", "日本語"),
    TranslationLanguageOption("ko", "한국어"),
    TranslationLanguageOption("fr", "Français"),
    TranslationLanguageOption("de", "Deutsch"),
    TranslationLanguageOption("es", "Español"),
    TranslationLanguageOption("it", "Italiano"),
)

private fun translationLanguageLabel(tag: String): String =
    translationLanguageOptions.firstOrNull { it.tag == tag }?.displayName ?: tag

/** RikkaHub-style actions shown once under every completed assistant reply. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssistantMessageActions(
    messageMarkdown: String,
    translation: String?,
    translationLanguage: String?,
    isTranslating: Boolean,
    onRegenerate: (() -> Unit)?,
    onReadAloud: () -> Unit,
    onTranslate: (String) -> Unit,
    onClearTranslation: () -> Unit,
    onEdit: (String) -> Unit,
    onCreateBranch: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var showMore by remember { mutableStateOf(false) }
    var showLanguagePicker by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }
    var editText by remember(messageMarkdown) { mutableStateOf(messageMarkdown) }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (!translation.isNullOrBlank()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = buildString {
                            append(context.getString(R.string.message_translation_label))
                            translationLanguage?.takeIf { it.isNotBlank() }?.let {
                                append(" · ")
                                append(translationLanguageLabel(it))
                            }
                        },
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    MarkdownBlock(rawText = translation, isStreaming = false)
                }
            }
        }

        Row(
            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MessageActionButton(
                icon = Icons.Default.ContentCopy,
                description = context.getString(R.string.message_action_copy),
            ) { clipboard.setText(AnnotatedString(messageMarkdown)) }
            MessageActionButton(
                icon = Icons.Default.Refresh,
                description = context.getString(R.string.message_action_regenerate),
                enabled = onRegenerate != null,
            ) { onRegenerate?.invoke() }
            MessageActionButton(
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                description = context.getString(R.string.message_action_read_aloud),
                onClick = onReadAloud,
            )
            MessageActionButton(
                icon = Icons.Default.Language,
                description = context.getString(R.string.message_action_translate),
                enabled = !isTranslating,
            ) { showLanguagePicker = true }
            MessageActionButton(
                icon = Icons.Default.MoreVert,
                description = context.getString(R.string.message_action_more),
            ) { showMore = true }
            if (isTranslating) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp)
            }
        }
    }

    if (showLanguagePicker) {
        ModalBottomSheet(onDismissRequest = { showLanguagePicker = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = context.getString(R.string.message_action_translate),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
                translationLanguageOptions.forEach { language ->
                    MessageMoreAction(Icons.Default.Language, language.displayName) {
                        showLanguagePicker = false
                        onTranslate(language.tag)
                    }
                }
                if (!translation.isNullOrBlank()) {
                    MessageMoreAction(
                        Icons.Default.Close,
                        context.getString(R.string.message_translation_clear),
                    ) {
                        showLanguagePicker = false
                        onClearTranslation()
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (showMore) {
        ModalBottomSheet(onDismissRequest = { showMore = false }) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MessageMoreAction(Icons.Default.Public, context.getString(R.string.message_action_web_render)) {
                    showMore = false
                    openReplyInBrowser(context, messageMarkdown)
                }
                MessageMoreAction(Icons.Default.Edit, context.getString(R.string.message_action_edit)) {
                    showMore = false
                    showEdit = true
                }
                MessageMoreAction(Icons.Default.Share, context.getString(R.string.message_action_share)) {
                    showMore = false
                    shareReply(context, messageMarkdown)
                }
                MessageMoreAction(Icons.Default.AccountTree, context.getString(R.string.message_action_create_branch)) {
                    showMore = false
                    onCreateBranch()
                }
                MessageMoreAction(
                    Icons.Default.Delete,
                    context.getString(R.string.message_action_delete),
                    destructive = true,
                ) {
                    showMore = false
                    onDelete()
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (showEdit) {
        AlertDialog(
            onDismissRequest = { showEdit = false },
            title = { Text(context.getString(R.string.message_action_edit)) },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6,
                    maxLines = 16,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onEdit(editText.trim())
                        showEdit = false
                    },
                    enabled = editText.isNotBlank(),
                ) { Text(context.getString(R.string.common_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showEdit = false }) {
                    Text(context.getString(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun MessageActionButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(32.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(17.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MessageMoreAction(
    icon: ImageVector,
    label: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val foreground = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    val background = if (destructive) {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        color = background,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(22.dp))
            Text(label, color = foreground, fontSize = 16.sp)
        }
    }
}

private fun shareReply(context: android.content.Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.message_action_share)))
}

private fun openReplyInBrowser(context: android.content.Context, text: String) {
    val escaped = android.text.TextUtils.htmlEncode(text.take(100_000))
    val html = """<!doctype html><meta name="viewport" content="width=device-width"><style>body{font-family:sans-serif;line-height:1.6;padding:20px;max-width:820px;margin:auto;white-space:pre-wrap;color:#202124}pre{overflow:auto}</style><body>$escaped</body>"""
    val uri = Uri.parse("data:text/html;charset=utf-8," + Uri.encode(html))
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
}
