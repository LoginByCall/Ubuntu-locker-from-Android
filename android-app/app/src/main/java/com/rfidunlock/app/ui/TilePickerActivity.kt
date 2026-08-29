package com.rfidunlock.app.ui

import android.content.ComponentName
import android.os.Bundle
import android.service.quicksettings.TileService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.rfidunlock.app.RfidApp
import com.rfidunlock.app.service.PcTileService

/**
 * Выбор ПК для плитки шторки ([PcTileService]).
 *
 * Открывается тапом по ненастроенной плитке (extra [EXTRA_SLOT]) или долгим
 * нажатием по любой плитке (QS_TILE_PREFERENCES: слот определяется по
 * ComponentName плитки из intent).
 */
class TilePickerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_SLOT = "slot"
        private const val EXTRA_QS_COMPONENT = "android.intent.extra.COMPONENT_NAME"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val slot = resolveSlot()
        if (slot == null) {
            finish()
            return
        }
        val repository = (application as RfidApp).pcProfileRepository
        setContent {
            MaterialTheme {
                val profiles by repository.profiles.collectAsState(initial = emptyList())
                AlertDialog(
                    onDismissRequest = { finish() },
                    title = { Text("Плитка $slot — выбор ПК") },
                    text = {
                        Column {
                            if (profiles.isEmpty()) {
                                Text("Профилей ПК нет. Отсканируйте QR-код в приложении.")
                            }
                            profiles.forEach { profile ->
                                TextButton(onClick = { save(slot, profile.id) }) {
                                    Text(profile.name)
                                }
                            }
                            TextButton(onClick = { save(slot, null) }) {
                                Text("Очистить плитку")
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = { finish() }) { Text("Отмена") }
                    },
                )
            }
        }
    }

    private fun resolveSlot(): Int? {
        val direct = intent.getIntExtra(EXTRA_SLOT, -1)
        if (direct in 1..PcTileService.SLOT_CLASSES.size) return direct
        @Suppress("DEPRECATION")
        val component = intent.getParcelableExtra<ComponentName>(EXTRA_QS_COMPONENT)
        val index = PcTileService.SLOT_CLASSES.indexOfFirst { it.name == component?.className }
        return if (index >= 0) index + 1 else null
    }

    private fun save(slot: Int, profileId: String?) {
        getSharedPreferences(PcTileService.PREFS, MODE_PRIVATE).edit()
            .putString(PcTileService.prefKey(slot), profileId)
            .apply()
        TileService.requestListeningState(
            this, ComponentName(this, PcTileService.SLOT_CLASSES[slot - 1])
        )
        finish()
    }
}
