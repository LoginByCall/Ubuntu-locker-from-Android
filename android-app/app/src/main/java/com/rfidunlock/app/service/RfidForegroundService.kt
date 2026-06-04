package com.rfidunlock.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.rfidunlock.app.R
import com.rfidunlock.app.RfidApp
import com.rfidunlock.app.data.ServerSettings
import com.rfidunlock.app.data.SettingsRepository
import com.rfidunlock.app.net.CommandResult
import com.rfidunlock.app.net.TcpCommandClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground-сервис: координирует отправку команд LOCK/UNLOCK на ПК по
 * собственному TCP-каналу. NFC и акселерометр живут в Activity и дёргают
 * сервис через [LocalBinder].
 */
class RfidForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "rfid_agent"
        private const val NOTIFICATION_ID = 1
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job)
    private val client = TcpCommandClient()
    private lateinit var settings: SettingsRepository

    /** Последний результат операции (для индикации в UI). */
    private val _lastResult = MutableStateFlow<CommandResult?>(null)
    val lastResult: StateFlow<CommandResult?> = _lastResult

    inner class LocalBinder : Binder() {
        val service: RfidForegroundService get() = this@RfidForegroundService
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        settings = (application as RfidApp).settingsRepository
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Отправить команду разблокировки на ПК. */
    fun requestUnlock() = dispatch { client.unlock(it) }

    /** Отправить команду блокировки на ПК. */
    fun requestLock() = dispatch { client.lock(it) }

    /** Проверить связь с ПК. */
    fun checkStatus() = dispatch { client.status(it) }

    private fun dispatch(action: suspend (ServerSettings) -> CommandResult) {
        scope.launch {
            val current = settings.settings.first()
            _lastResult.value = action(current)
        }
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
}
