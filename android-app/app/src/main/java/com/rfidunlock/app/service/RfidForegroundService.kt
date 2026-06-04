package com.rfidunlock.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.rfidunlock.app.R
import com.rfidunlock.app.RfidApp
import com.rfidunlock.app.kdeconnect.DeviceIdentity
import com.rfidunlock.app.kdeconnect.KdeConnectClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Foreground-сервис: удерживает соединение KDE Connect с ПК и выполняет
 * отправку команд LOCK/UNLOCK. NFC и акселерометр живут в Activity и
 * дёргают сервис через [LocalBinder].
 */
class RfidForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "rfid_agent"
        private const val NOTIFICATION_ID = 1
        const val COMMAND_LOCK = "rfid-lock"
        const val COMMAND_UNLOCK = "rfid-unlock"
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job)
    private lateinit var client: KdeConnectClient

    inner class LocalBinder : Binder() {
        val service: RfidForegroundService get() = this@RfidForegroundService
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        val identity = DeviceIdentity.loadOrCreate(this, "RFID Unlock (${Build.MODEL})")
        client = KdeConnectClient(identity, scope)
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        client.start()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        client.stop()
        scope.cancel()
        super.onDestroy()
    }

    /** Отправить команду разблокировки на ПК. */
    fun requestUnlock() = client.sendCommand(COMMAND_UNLOCK)

    /** Отправить команду блокировки на ПК. */
    fun requestLock() = client.sendCommand(COMMAND_LOCK)

    /** Инициировать сопряжение с ПК. */
    fun pair() = client.requestPair()

    val connectionState get() = client.state
    val confirmations get() = client.incomingPings

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
