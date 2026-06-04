package com.rfidunlock.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import com.rfidunlock.app.data.TagRepository
import com.rfidunlock.app.nfc.MotionTrigger
import com.rfidunlock.app.nfc.NfcController
import com.rfidunlock.app.service.RfidForegroundService
import com.rfidunlock.app.ui.NameTagDialog
import com.rfidunlock.app.ui.TagListScreen
import com.rfidunlock.app.ui.TagViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val repository: TagRepository by lazy { (application as RfidApp).tagRepository }
    private val viewModel: TagViewModel by viewModels {
        TagViewModel.Factory(repository)
    }

    private var service: RfidForegroundService? = null
    private lateinit var nfc: NfcController
    private lateinit var motion: MotionTrigger

    /** UID только что обнаружённой неизвестной метки (для диалога имени). */
    private var pendingUid by mutableStateOf<String?>(null)

    /** UID последней активной метки на устройстве (для команды LOCK при снятии). */
    @Volatile
    private var lastEnabledUid: String? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as RfidForegroundService.LocalBinder).service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        nfc = NfcController(
            onTagAttached = ::handleTagAttached,
            onTagRemoved = ::handleTagRemoved,
        )
        motion = MotionTrigger(this) { nfc.checkTagPresence() }

        startForegroundService(Intent(this, RfidForegroundService::class.java))
        bindService(
            Intent(this, RfidForegroundService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )

        setContent {
            val context = LocalContext.current
            val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dynamicLightColorScheme(context)
            } else {
                lightColorScheme()
            }
            MaterialTheme(colorScheme = colorScheme) {
                Surface {
                    TagListScreen(viewModel)
                    val uid = pendingUid
                    if (uid != null) {
                        NameTagDialog(
                            uid = uid,
                            onConfirm = { name ->
                                viewModel.registerOrRename(uid, name)
                                pendingUid = null
                            },
                            onDismiss = { pendingUid = null },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        nfc.enable(this)
        motion.start()
    }

    override fun onPause() {
        super.onPause()
        nfc.disable(this)
        motion.stop()
    }

    override fun onDestroy() {
        runCatching { unbindService(connection) }
        super.onDestroy()
    }

    /** Метка поднесена: если известна и активна — разблокировать ПК; иначе предложить добавить. */
    private fun handleTagAttached(uid: String) {
        lifecycleScope.launch {
            val tag = repository.findByUid(uid)
            when {
                tag == null -> pendingUid = uid
                tag.enabled -> {
                    lastEnabledUid = uid
                    service?.requestUnlock()
                }
                else -> Unit // метка известна, но выключена — игнор
            }
        }
    }

    /** Метка снята: если последняя метка была активна — заблокировать ПК. */
    private fun handleTagRemoved() {
        if (lastEnabledUid == null) return
        lastEnabledUid = null
        service?.requestLock()
    }
}
