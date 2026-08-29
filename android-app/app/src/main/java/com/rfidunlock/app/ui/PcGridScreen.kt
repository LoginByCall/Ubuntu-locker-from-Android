package com.rfidunlock.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rfidunlock.app.R
import com.rfidunlock.app.data.PcProfile
import com.rfidunlock.app.data.PcStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PcGridScreen(
    viewModel: PcGridViewModel,
    onOpenTags: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onAddPc: () -> Unit = {},
) {
    val profiles by viewModel.profiles.collectAsState()
    val statuses by viewModel.statuses.collectAsState()

    // Профиль, для которого открыт диалог сведений (null — диалог закрыт).
    var detailsProfile by remember { mutableStateOf<PcProfile?>(null) }
    // Профиль, для которого запрошено подтверждение удаления.
    var deletingProfile by remember { mutableStateOf<PcProfile?>(null) }

    // Запросить статусы один раз при открытии экрана (по требованию, без фонового опроса).
    LaunchedEffect(profiles.size) {
        if (profiles.isNotEmpty()) viewModel.refresh()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Мои ПК") },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Обновить статусы")
                    }
                    IconButton(onClick = onAddPc) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = "Добавить ПК (QR)")
                    }
                    IconButton(onClick = onOpenTags) {
                        Icon(Icons.Default.Nfc, contentDescription = "Метки RFID")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                    }
                },
            )
        }
    ) { padding ->
        if (profiles.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Нет добавленных ПК.\nНажмите кнопку сканирования и считайте QR-код, " +
                        "показанный агентом на ПК.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(profiles, key = { it.id }) { profile ->
                    PcTile(
                        profile = profile,
                        status = statuses[profile.id] ?: PcStatus.UNKNOWN,
                        onClick = { viewModel.toggle(profile) },
                        onShowDetails = { detailsProfile = profile },
                        onDelete = { deletingProfile = profile },
                    )
                }
            }
        }
    }

    // Диалог сведений о ПК.
    detailsProfile?.let { profile ->
        AlertDialog(
            onDismissRequest = { detailsProfile = null },
            confirmButton = {
                TextButton(onClick = { detailsProfile = null }) { Text("Закрыть") }
            },
            dismissButton = {
                TextButton(onClick = {
                    detailsProfile = null
                    deletingProfile = profile
                }) { Text("Удалить") }
            },
            title = { Text(profile.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Адрес: ${profile.hostPort()}")
                    Text("ОС: ${profile.os.ifEmpty { "—" }}")
                    Text("ID: ${profile.id}", style = MaterialTheme.typography.bodySmall)
                }
            },
        )
    }

    // Подтверждение удаления профиля.
    deletingProfile?.let { profile ->
        AlertDialog(
            onDismissRequest = { deletingProfile = null },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(profile)
                    deletingProfile = null
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { deletingProfile = null }) { Text("Отмена") }
            },
            title = { Text("Удалить ПК?") },
            text = { Text("Профиль «${profile.name}» будет удалён из приложения.") },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun PcTile(
    profile: PcProfile,
    status: PcStatus,
    onClick: () -> Unit,
    onShowDetails: () -> Unit,
    onDelete: () -> Unit,
) {
    val (icon, tint, label) = statusVisuals(status)
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { menuOpen = true },
                ),
            colors = CardDefaults.cardColors(),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(modifier = Modifier.size(56.dp)) {
                    // Иконка ОС — основной визуальный признак ПК.
                    Icon(
                        painter = painterResource(id = osIconRes(profile.os)),
                        contentDescription = profile.os.ifEmpty { "ОС" },
                        tint = Color.Unspecified,
                        modifier = Modifier.size(56.dp),
                    )
                    // Бейдж статуса замка в правом нижнем углу.
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = tint,
                        modifier = Modifier
                            .size(22.dp)
                            .align(Alignment.BottomEnd)
                            .offset(x = 4.dp, y = 4.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(2.dp),
                    )
                }
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = tint,
                )
            }
        }
        // Контекстное меню по долгому нажатию: сведения / удаление.
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Сведения") },
                onClick = {
                    menuOpen = false
                    onShowDetails()
                },
            )
            DropdownMenuItem(
                text = { Text("Удалить ПК") },
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
}

/** Сопоставить семейство ОС с векторной иконкой-логотипом. */
private fun osIconRes(os: String): Int = when (os.lowercase()) {
    "ubuntu" -> R.drawable.ic_os_ubuntu
    "windows" -> R.drawable.ic_os_windows
    "macos" -> R.drawable.ic_os_macos
    else -> R.drawable.ic_os_linux
}

private data class TileVisuals(val icon: ImageVector, val tint: Color, val label: String)

@Composable
private fun statusVisuals(status: PcStatus): TileVisuals = when (status) {
    PcStatus.LOCKED -> TileVisuals(Icons.Default.Lock, Color(0xFFC62828), "Заблокирован")
    PcStatus.UNLOCKED -> TileVisuals(Icons.Default.LockOpen, Color(0xFF2E7D32), "Разблокирован")
    PcStatus.OFFLINE -> TileVisuals(Icons.Default.CloudOff, Color(0xFF9E9E9E), "Недоступен")
    PcStatus.UNKNOWN -> TileVisuals(Icons.Default.HelpOutline, Color(0xFF9E9E9E), "Нажмите «Обновить»")
}
