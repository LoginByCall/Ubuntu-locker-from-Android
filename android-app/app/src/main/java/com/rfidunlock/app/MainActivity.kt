package com.rfidunlock.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.registerForActivityResult
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
import com.rfidunlock.app.data.PcProfileRepository
import com.rfidunlock.app.data.ProfileQr
import com.rfidunlock.app.data.ServerSettings
import com.rfidunlock.app.data.SettingsRepository
import com.rfidunlock.app.data.TagMode
import com.rfidunlock.app.data.TagRepository
import com.rfidunlock.app.nfc.MotionTrigger
import com.rfidunlock.app.nfc.NfcController
import com.rfidunlock.app.service.RfidForegroundService
import com.rfidunlock.app.ui.NameTagDialog
import com.rfidunlock.app.ui.SettingsScreen
import com.rfidunlock.app.ui.SettingsViewModel
import com.rfidunlock.app.ui.TagListScreen
import com.rfidunlock.app.ui.TagViewModel
import com.rfidunlock.app.ui.PcGridScreen
import com.rfidunlock.app.ui.PcGridViewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val repository: TagRepository by lazy { (application as RfidApp).tagRepository }
    private val settingsRepository: SettingsRepository by lazy {
        (application as RfidApp).settingsRepository
    }
    private val pcProfileRepository: PcProfileRepository by lazy {
        (application as RfidApp).pcProfileRepository
    }
    private val viewModel: TagViewModel by viewModels {
        TagViewModel.Factory(repository, pcProfileRepository)
    }
    private val settingsViewModel: SettingsViewModel by viewModels {
        SettingsViewModel.Factory(settingsRepository, pcProfileRepository)
    }
    private val pcGridViewModel: PcGridViewModel by viewModels {
        PcGridViewModel.Factory(pcProfileRepository)
    }

    private var service: RfidForegroundService? = null
    private lateinit var nfc: NfcController
    private lateinit var motion: MotionTrigger

    /** Запуск сканера QR-кода профиля ПК (ZXing). */
    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { handleScannedProfile(it) }
    }

    /** Вибромотор для тактильного отклика при считывании метки. */
    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    /** UID только что обнаружённой неизвестной метки (для диалога имени). */
    private var pendingUid by mutableStateOf<String?>(null)

    /** Текущий экран. */
    private enum class Screen { GRID, TAGS, SETTINGS }
    private var screen by mutableStateOf(Screen.GRID)

    /** Текст последнего результата операции (для индикации в настройках). */
    private var statusText by mutableStateOf<String?>(null)

    /** UID последней активной метки на устройстве (для команды LOCK при снятии). */
    @Volatile
    private var lastEnabledUid: String? = null

    /** Целевой ПК для LOCK при снятии метки в режиме «Присутствие». */
    @Volatile
    private var lastEnabledTarget: ServerSettings? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as RfidForegroundService.LocalBinder).service
            service = svc
            lifecycleScope.launch {
                svc.lastResult.collect { result ->
                    statusText = result?.let {
                        if (it.ok) "Связь с ПК: OK (${it.detail})"
                        else "Ошибка: ${it.detail}"
                    }
                }
            }
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

        // Не давать экрану гаснуть: NFC Reader Mode активен только пока Activity
        // на переднем плане. Для сценария «телефон на подставке с меткой» держим
        // экран включённым, чтобы метка непрерывно считывалась без действий пользователя.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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
                    when (screen) {
                        Screen.SETTINGS -> SettingsScreen(
                            viewModel = settingsViewModel,
                            statusText = statusText,
                            onBack = { screen = Screen.GRID },
                            onTestConnection = { service?.checkStatus() },
                        )
                        Screen.TAGS -> {
                            TagListScreen(
                                viewModel = viewModel,
                                onOpenSettings = { screen = Screen.SETTINGS },
                                onAddPc = { startQrScan() },
                            )
                        }
                        Screen.GRID -> {
                            PcGridScreen(
                                viewModel = pcGridViewModel,
                                onOpenTags = { screen = Screen.TAGS },
                                onOpenSettings = { screen = Screen.SETTINGS },
                                onAddPc = { startQrScan() },
                            )
                        }
                    }
                    // Диалог имени новой метки — поверх любого экрана.
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

        // Если приложение запущено по поднесению метки (intent от системы) — обработать UID.
        handleNfcIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNfcIntent(intent)
    }

    /** Извлечь UID из NFC-intent (когда приложение открыто системой по метке). */
    private fun handleNfcIntent(intent: Intent?) {
        val action = intent?.action ?: return
        if (action != NfcAdapter.ACTION_TAG_DISCOVERED &&
            action != NfcAdapter.ACTION_TECH_DISCOVERED &&
            action != NfcAdapter.ACTION_NDEF_DISCOVERED
        ) return

        @Suppress("DEPRECATION")
        val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
        val uid = tag?.id?.joinToString("") { "%02X".format(it) } ?: return
        handleTagAttached(uid)
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

    /** Метка поднесена: поведение зависит от выбранной логики метки. */
    private fun handleTagAttached(uid: String) {
        vibrateOnRead()
        lifecycleScope.launch {
            val tag = repository.findByUid(uid)
            when {
                tag == null -> pendingUid = uid
                !tag.enabled -> Unit // метка известна, но выключена — игнор
                tag.profileId == null -> {
                    // «Универсальная» метка — просто открыть экран плиток ПК.
                    lastEnabledUid = null
                    lastEnabledTarget = null
                    screen = Screen.GRID
                }
                else -> {
                    val profile = pcProfileRepository.findById(tag.profileId)
                    if (profile == null) {
                        Toast.makeText(
                            this@MainActivity,
                            "Профиль ПК для метки не найден",
                            Toast.LENGTH_SHORT,
                        ).show()
                        return@launch
                    }
                    val target = profile.toServerSettings()
                    when (tag.mode) {
                        TagMode.TOGGLE -> {
                            // Поочерёдно: LOCK / UNLOCK.
                            if (tag.toggleNextLock) service?.requestLock(target)
                            else service?.requestUnlock(target)
                            repository.setToggleNextLock(uid, !tag.toggleNextLock)
                            lastEnabledUid = null
                            lastEnabledTarget = null
                        }
                        else -> {
                            // Режим «Присутствие»: поднесение — UNLOCK, снятие — LOCK.
                            lastEnabledUid = uid
                            lastEnabledTarget = target
                            service?.requestUnlock(target)
                        }
                    }
                }
            }
        }
    }

    /** Метка снята: блокировать ПК только для метки в режиме «Присутствие». */
    private fun handleTagRemoved() {
        val target = lastEnabledTarget ?: return
        lastEnabledUid = null
        lastEnabledTarget = null
        service?.requestLock(target)
    }

    /** Запустить сканер QR-кода профиля ПК. */
    private fun startQrScan() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Наведите камеру на QR-код профиля ПК")
            setBeepEnabled(false)
            setOrientationLocked(false)
        }
        qrScanLauncher.launch(options)
    }

    /** Обработать отсканированный QR: разобрать и сохранить профиль ПК. */
    private fun handleScannedProfile(raw: String) {
        when (val result = ProfileQr.parse(raw)) {
            is ProfileQr.Result.Ok -> lifecycleScope.launch {
                pcProfileRepository.save(result.profile)
                Toast.makeText(
                    this@MainActivity,
                    "ПК добавлен: ${result.profile.name}",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            is ProfileQr.Result.Error ->
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
        }
    }

    /** Короткий вибро-отклик в момент считывания метки. */
    private fun vibrateOnRead() {
        runCatching {
            if (!vibrator.hasVibrator()) return
            val effect = VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE)
            vibrator.vibrate(effect)
        }
    }
}
