package com.rfidunlock.app.confirm

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.rfidunlock.app.R
import com.rfidunlock.app.RfidApp
import com.rfidunlock.app.net.TcpCommandClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Подтверждение действий на ПК (sudo и т. п.) со смартфона.
 *
 * ПК шлёт push с идентификатором запроса, телефон показывает уведомление с
 * кнопками, вердикт уходит обратно по обычному TCP-каналу с HMAC-подписью.
 * В push нет ни пароля, ни токена — только id запроса и текст приглашения,
 * поэтому доступ к push-сервису подтвердить ничего не позволяет.
 *
 * «Подтвердить» открывает [ConfirmActivity]: на заблокированном телефоне
 * Android сам потребует разблокировку перед запуском Activity — отдельная
 * биометрия не нужна. «Отклонить» уходит сразу, без разблокировки.
 */
object ConfirmPush {

    private const val TAG = "ConfirmPush"
    const val CHANNEL_ID = "confirm"
    const val EXTRA_ASK_ID = "askId"
    const val EXTRA_PC_ID = "pcId"
    const val EXTRA_APPROVE = "approve"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = TcpCommandClient()

    /** Сообщить всем известным ПК push-токен этого телефона. */
    fun registerWithPcs(app: RfidApp) {
        scope.launch {
            val token = runCatching { FirebaseMessaging.getInstance().token.await() }
                .getOrElse {
                    Log.i(TAG, "push недоступен (нет google-services.json?): ${it.message}")
                    return@launch
                }
            // Параллельно: недоступный ПК держит соединение до ~3 минут и при
            // последовательной регистрации не давал зарегистрироваться остальным.
            coroutineScope {
                app.pcProfileRepository.profilesOnce().forEach { profile ->
                    launch {
                        val result = client.register(profile.toServerSettings(), token)
                        Log.i(TAG, "register на ${profile.name}: ${result.ok} ${result.detail}")
                    }
                }
            }
        }
    }

    /** Отправить вердикт ПК, приславшему запрос. */
    fun sendVerdict(context: Context, askId: String, pcId: String, approve: Boolean) {
        val app = context.applicationContext as RfidApp
        NotificationManagerCompat.from(context).cancel(askId.hashCode())
        scope.launch {
            val profile = app.pcProfileRepository.findById(pcId)
            if (profile == null) {
                Log.w(TAG, "нет профиля ПК $pcId — вердикт некуда слать")
                return@launch
            }
            val result = client.confirm(profile.toServerSettings(), askId, approve)
            Log.i(TAG, "вердикт ${if (approve) "approve" else "deny"} -> ${result.detail}")
        }
    }

    fun showRequest(context: Context, data: Map<String, String>) {
        val askId = data["askId"].orEmpty()
        val pcId = data["pcId"].orEmpty()
        if (askId.isEmpty()) return
        val prompt = data["prompt"].orEmpty().ifEmpty { "Подтвердить действие?" }
        val host = data["host"].orEmpty()
        val expiresAt = (data["expires"]?.toLongOrNull() ?: 0L) * 1000

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Подтверждения с ПК", NotificationManager.IMPORTANCE_HIGH)
        )

        val approve = PendingIntent.getActivity(
            context, askId.hashCode(),
            Intent(context, ConfirmActivity::class.java)
                .putExtra(EXTRA_ASK_ID, askId).putExtra(EXTRA_PC_ID, pcId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val deny = PendingIntent.getBroadcast(
            context, askId.hashCode() + 1,
            Intent(context, ConfirmDenyReceiver::class.java)
                .putExtra(EXTRA_ASK_ID, askId).putExtra(EXTRA_PC_ID, pcId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_qs_lock)
            .setContentTitle(if (host.isEmpty()) "Запрос подтверждения" else "Запрос с $host")
            .setContentText(prompt)
            .setStyle(NotificationCompat.BigTextStyle().bigText(prompt))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .addAction(0, "Подтвердить", approve)
            .addAction(0, "Отклонить", deny)
            .apply {
                // Уведомление само исчезает, когда ПК уже перестал ждать ответ.
                if (expiresAt > System.currentTimeMillis()) {
                    setTimeoutAfter(expiresAt - System.currentTimeMillis())
                }
            }
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(askId.hashCode(), notification) }
            .onFailure { Log.w(TAG, "нет разрешения на уведомления: ${it.message}") }
    }
}

/** Приём push с ПК. */
class ConfirmMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] != "confirm") return
        ConfirmPush.showRequest(this, message.data)
    }

    override fun onNewToken(token: String) {
        ConfirmPush.registerWithPcs(application as RfidApp)
    }
}

/** «Отклонить» — без разблокировки телефона. */
class ConfirmDenyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ConfirmPush.sendVerdict(
            context,
            intent.getStringExtra(ConfirmPush.EXTRA_ASK_ID).orEmpty(),
            intent.getStringExtra(ConfirmPush.EXTRA_PC_ID).orEmpty(),
            approve = false,
        )
    }
}

/**
 * «Подтвердить». Отдельная Activity нужна ради разблокировки: запуск Activity
 * с экрана блокировки Android пропускает только после аутентификации хозяина.
 */
class ConfirmActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConfirmPush.sendVerdict(
            this,
            intent.getStringExtra(ConfirmPush.EXTRA_ASK_ID).orEmpty(),
            intent.getStringExtra(ConfirmPush.EXTRA_PC_ID).orEmpty(),
            approve = true,
        )
        Toast.makeText(this, "Подтверждено", Toast.LENGTH_SHORT).show()
        finish()
    }
}
