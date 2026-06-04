package com.rfidunlock.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rfidunlock.app.data.PcProfile
import com.rfidunlock.app.data.Tag
import com.rfidunlock.app.data.TagMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagListScreen(
    viewModel: TagViewModel,
    onOpenSettings: () -> Unit = {},
    onAddPc: () -> Unit = {},
) {
    val tags by viewModel.tags.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    var editingTag by remember { mutableStateOf<Tag?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Метки RFID") },
                actions = {
                    IconButton(onClick = onAddPc) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = "Добавить ПК (QR)")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                    }
                },
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (tags.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Поднесите смартфон к NFC-метке, чтобы добавить её.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(tags, key = { it.uid }) { tag ->
                        TagRow(
                            tag = tag,
                            profiles = profiles,
                            onToggle = { viewModel.setEnabled(tag, it) },
                            onModeChange = { viewModel.setMode(tag, it) },
                            onProfileChange = { viewModel.setProfile(tag, it) },
                            onEdit = { editingTag = tag },
                            onDelete = { viewModel.delete(tag) },
                        )
                    }
                }
            }
        }
    }

    val editing = editingTag
    if (editing != null) {
        NameTagDialog(
            uid = editing.uid,
            initialName = editing.name,
            title = "Изменить имя метки",
            onConfirm = { name ->
                viewModel.registerOrRename(editing.uid, name)
                editingTag = null
            },
            onDismiss = { editingTag = null },
        )
    }
}

@Composable
private fun TagRow(
    tag: Tag,
    profiles: List<PcProfile>,
    onToggle: (Boolean) -> Unit,
    onModeChange: (TagMode) -> Unit,
    onProfileChange: (String?) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(tag.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        tag.uid,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = tag.enabled, onCheckedChange = onToggle)
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Изменить имя")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Удалить")
                }
            }
            Text(
                "Логика срабатывания:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = tag.mode == TagMode.PRESENCE,
                    onClick = { onModeChange(TagMode.PRESENCE) },
                    label = { Text("Присутствие") },
                )
                FilterChip(
                    selected = tag.mode == TagMode.TOGGLE,
                    onClick = { onModeChange(TagMode.TOGGLE) },
                    label = { Text("Переключение") },
                )
            }
            ProfilePicker(
                profiles = profiles,
                selectedId = tag.profileId,
                onSelect = onProfileChange,
            )
        }
    }
}

@Composable
private fun ProfilePicker(
    profiles: List<PcProfile>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val currentName = profiles.firstOrNull { it.id == selectedId }?.name
        ?: "Универсальная (любой ПК)"
    Text(
        "Привязка к ПК:",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
    Row(modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = { expanded = true }) {
            Text(currentName)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Универсальная (любой ПК)") },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            profiles.forEach { profile ->
                DropdownMenuItem(
                    text = { Text(profile.name) },
                    onClick = {
                        onSelect(profile.id)
                        expanded = false
                    },
                )
            }
        }
    }
}
