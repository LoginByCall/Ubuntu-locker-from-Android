package com.rfidunlock.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Экран настроек подключения к ПК (IP, порт, токен) + проверка связи. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    statusText: String?,
    onBack: () -> Unit,
    onTestConnection: () -> Unit,
) {
    val settings by viewModel.settings.collectAsState()
    val profiles by viewModel.profiles.collectAsState()

    var host by remember { mutableStateOf(settings.host) }
    var port by remember { mutableStateOf(settings.port.toString()) }
    var token by remember { mutableStateOf(settings.token) }

    // Подхватить значения, когда они подгрузятся из DataStore.
    LaunchedEffect(settings) {
        host = settings.host
        port = settings.port.toString()
        token = settings.token
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки ПК") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Профили ПК", style = MaterialTheme.typography.titleMedium)
            if (profiles.isEmpty()) {
                Text(
                    "Нет добавленных профилей. Добавьте ПК, сканировав QR-код из трея агента.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                profiles.forEach { profile ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(profile.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    profile.hostPort(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { viewModel.deleteProfile(profile) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Удалить профиль")
                            }
                        }
                    }
                }
            }

            HorizontalDivider()
            Text(
                "Ручное подключение (резерв)",
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("IP-адрес ПК") },
                placeholder = { Text("192.168.0.10") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit).take(5) },
                label = { Text("Порт") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Токен") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    viewModel.save(host, port.toIntOrNull() ?: 5390, token)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Сохранить") }

            OutlinedButton(
                onClick = {
                    viewModel.save(host, port.toIntOrNull() ?: 5390, token) {
                        onTestConnection()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Проверить связь") }

            if (statusText != null) {
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Text("Встроенный ZeroTier", style = MaterialTheme.typography.titleMedium)
            Text(
                com.rfidunlock.app.net.ZtEmbedded.nodeId()
                    ?.let {
                        "Node ID: $it\nАвторизуйте этот узел на контроллере сети " +
                            "ZeroTier — тогда связь с ПК работает без VPN-приложения."
                    }
                    ?: "Узел ещё не создан — появится после первой команды, " +
                        "когда системный ZT-туннель недоступен.",
                style = MaterialTheme.typography.bodySmall,
            )

            // Версия из VERSION в корне репозитория: агент и приложение
            // выпускаются вместе, номер должен совпадать с `rfid-server.py --version`.
            HorizontalDivider()
            Text(
                "Версия приложения: ${com.rfidunlock.app.BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
