package com.openminis.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.agent.AssistantProfile
import com.openminis.app.agent.AssistantProfileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AssistantProfilesScreen(
    onBack: () -> Unit,
    onProfileClick: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profiles by AssistantProfileStore.profiles.collectAsState()
    val activeId by AssistantProfileStore.activeProfileId.collectAsState()
    var actionProfile by remember { mutableStateOf<AssistantProfile?>(null) }
    var deleteProfile by remember { mutableStateOf<AssistantProfile?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var addName by remember { mutableStateOf("") }
    var addIcon by remember { mutableStateOf("") }
    var isCreating by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { AssistantProfileStore.ensure(context) }
    }

    SettingsScaffold(
        title = stringResource(R.string.assistant_profiles_title),
        onBack = onBack,
        actions = {
            IconButton(
                onClick = {
                    addName = ""
                    addIcon = ""
                    showAddDialog = true
                },
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.assistant_add))
            }
        },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            profiles.forEach { profile ->
                val selected = profile.id == activeId
                Surface(
                    color = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    shape = RoundedCornerShape(8.dp),
                    tonalElevation = 1.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        AssistantProfileStore.selectProfile(context, profile.id)
                                    }
                                }
                            },
                            onLongClick = { actionProfile = profile },
                        ),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AssistantAvatar(profile.metadata.icon, 42.dp, 30.dp, 24.sp)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = profile.metadata.name,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            profile.metadata.style.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    text = it,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                        if (selected) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = stringResource(R.string.assistant_active),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        val avatarChoices = listOf("", "🤖", "🧠", "💡", "🎨", "🧑‍💻")
        AlertDialog(
            onDismissRequest = { if (!isCreating) showAddDialog = false },
            title = { Text(stringResource(R.string.assistant_new_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        text = stringResource(R.string.assistant_avatar),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        avatarChoices.forEach { choice ->
                            val selected = addIcon == choice
                            Surface(
                                shape = CircleShape,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                border = BorderStroke(
                                    if (selected) 2.dp else 1.dp,
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                                ),
                                modifier = Modifier
                                    .size(48.dp)
                                    .combinedClickable(
                                        onClick = { addIcon = choice },
                                        onLongClick = {},
                                    ),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    SoulIconGlyph(choice, 30.dp, 24.sp)
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = addName,
                        onValueChange = { addName = it },
                        label = { Text(stringResource(R.string.assistant_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = addName.isNotBlank() && !isCreating,
                    onClick = {
                        val name = addName.trim()
                        val icon = addIcon
                        isCreating = true
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val id = AssistantProfileStore.createProfile(context, name, icon)
                                AssistantProfileStore.selectProfile(context, id)
                            }
                            isCreating = false
                            showAddDialog = false
                        }
                    },
                ) { Text(stringResource(R.string.assistant_create)) }
            },
            dismissButton = {
                TextButton(
                    enabled = !isCreating,
                    onClick = { showAddDialog = false },
                ) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }

    actionProfile?.let { profile ->
        val copiedName = stringResource(R.string.assistant_copy_name, profile.metadata.name)
        ModalBottomSheet(onDismissRequest = { actionProfile = null }) {
            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AssistantAvatar(profile.metadata.icon, 40.dp, 28.dp, 22.sp)
                    Spacer(Modifier.width(12.dp))
                    Text(profile.metadata.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
                AssistantActionRow(
                    icon = Icons.Default.ContentCopy,
                    label = stringResource(R.string.assistant_copy),
                    onClick = {
                        actionProfile = null
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                AssistantProfileStore.duplicateProfile(context, profile.id, copiedName)
                            }
                        }
                    },
                )
                AssistantActionRow(
                    icon = Icons.Default.Edit,
                    label = stringResource(R.string.assistant_edit),
                    onClick = {
                        actionProfile = null
                        onProfileClick(profile.id)
                    },
                )
                AssistantActionRow(
                    icon = Icons.Default.Delete,
                    label = stringResource(R.string.assistant_delete),
                    tint = MaterialTheme.colorScheme.error,
                    enabled = profiles.size > 1,
                    onClick = {
                        actionProfile = null
                        deleteProfile = profile
                    },
                )
                Spacer(Modifier.size(12.dp))
            }
        }
    }

    deleteProfile?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleteProfile = null },
            title = { Text(stringResource(R.string.assistant_delete_title)) },
            text = { Text(stringResource(R.string.assistant_delete_body, profile.metadata.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteProfile = null
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                AssistantProfileStore.deleteProfile(context, profile.id)
                            }
                        }
                    },
                ) {
                    Text(
                        stringResource(R.string.assistant_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteProfile = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun AssistantAvatar(icon: String, container: androidx.compose.ui.unit.Dp, glyph: androidx.compose.ui.unit.Dp, emoji: androidx.compose.ui.unit.TextUnit) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(container)) {
        Box(contentAlignment = Alignment.Center) {
            SoulIconGlyph(icon, glyph, emoji)
        }
    }
}

@Composable
private fun AssistantActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = {},
            )
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint.copy(alpha = if (enabled) 1f else 0.35f))
        Spacer(Modifier.width(14.dp))
        Text(label, color = tint.copy(alpha = if (enabled) 1f else 0.35f), fontSize = 16.sp)
    }
}
