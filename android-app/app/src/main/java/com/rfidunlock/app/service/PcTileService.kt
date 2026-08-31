package com.rfidunlock.app.service

import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.rfidunlock.app.RfidApp
import com.rfidunlock.app.data.PcProfile
import com.rfidunlock.app.data.PcStatus
import com.rfidunlock.app.net.TcpCommandClient
import com.rfidunlock.app.ui.TilePickerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Плитка быстрых настроек (шторка): управление одним ПК.
 *
 * Слотов четыре (PcTile1…PcTile4) — в шторку можно добавить несколько плиток,
 * каждой назначить свой ПК. Тап по ненастроенной плитке и долгое нажатие по
 * любой открывают выбор ПК ([TilePickerActivity]); тап по настроенной —
 * переключает блокировку (та же логика, что у плитки в приложении).
 * Выбор хранится в SharedPreferences [PREFS]: `tile_pc_<слот>` → id профиля.
 */
abstract class PcTileService(private val slot: Int) : TileService() {

    companion object {
        const val PREFS = "qs_tiles"
        fun prefKey(slot: Int) = "tile_pc_$slot"

        /** Классы слотов по порядку — для ComponentName и requestListeningState. */
        val SLOT_CLASSES: List<Class<out PcTileService>> = listOf(
            PcTile1::class.java, PcTile2::class.java, PcTile3::class.java, PcTile4::class.java,
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val client = TcpCommandClient()
    private var toggling = false

    private fun prefs(): SharedPreferences = getSharedPreferences(PREFS, MODE_PRIVATE)

    private suspend fun profile(): PcProfile? {
        val id = prefs().getString(prefKey(slot), null) ?: return null
        return (application as RfidApp).pcProfileRepository.findById(id)
    }

    override fun onStartListening() {
        scope.launch { refreshTile() }
    }

    override fun onClick() {
        if (toggling) return
        scope.launch {
            val p = profile()
            if (p == null) {
                openPicker()
                return@launch
            }
            toggling = true
            try {
                val settings = p.toServerSettings()
                when (queryStatus(p)) {
                    PcStatus.LOCKED -> client.unlock(settings)
                    PcStatus.UNLOCKED -> client.lock(settings)
                    else -> {
                        refreshTile()
                        return@launch
                    }
                }
                refreshTile()
                // ПК завершает переход (особенно разблокировку) с задержкой.
                delay(2000)
                refreshTile()
            } finally {
                toggling = false
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun refreshTile() {
        val tile = qsTile ?: return
        val p = profile()
        if (p == null) {
            tile.state = Tile.STATE_INACTIVE
            setLabels(tile, "Выбрать ПК", null)
        } else {
            when (queryStatus(p)) {
                PcStatus.UNLOCKED -> {
                    tile.state = Tile.STATE_ACTIVE
                    setLabels(tile, p.name, "разблокирован")
                }
                PcStatus.LOCKED -> {
                    tile.state = Tile.STATE_INACTIVE
                    setLabels(tile, p.name, "заблокирован")
                }
                else -> {
                    tile.state = Tile.STATE_INACTIVE
                    setLabels(tile, p.name, "офлайн")
                }
            }
        }
        tile.updateTile()
    }

    private fun setLabels(tile: Tile, label: String, subtitle: String?) {
        // Tile.setLabel/setSubtitle появились в API 30; на API 29 остаётся
        // статическая подпись из манифеста.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tile.label = label
            tile.subtitle = subtitle
        }
    }

    private suspend fun queryStatus(p: PcProfile): PcStatus {
        val result = client.status(p.toServerSettings())
        // ПК сообщает свой адрес в LAN — так быстрый путь появляется и у тех,
        // кто пользуется только плиткой и метками, не открывая экран ПК.
        (application as RfidApp).pcProfileRepository.setLan(p, result.lan)
        val detail = result.detail.lowercase()
        return when {
            !result.ok -> PcStatus.OFFLINE
            "locked=yes" in detail || "locked=true" in detail -> PcStatus.LOCKED
            "locked=no" in detail || "locked=false" in detail -> PcStatus.UNLOCKED
            else -> PcStatus.UNKNOWN
        }
    }

    private fun openPicker() {
        val intent = Intent(this, TilePickerActivity::class.java)
            .putExtra(TilePickerActivity.EXTRA_SLOT, slot)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, slot, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

class PcTile1 : PcTileService(1)
class PcTile2 : PcTileService(2)
class PcTile3 : PcTileService(3)
class PcTile4 : PcTileService(4)
