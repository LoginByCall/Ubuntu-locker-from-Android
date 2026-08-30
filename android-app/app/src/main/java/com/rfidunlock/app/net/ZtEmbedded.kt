package com.rfidunlock.app.net

import android.content.Context
import android.util.Log
import com.zerotier.sockets.ZeroTierNative
import com.zerotier.sockets.ZeroTierNode
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Встроенный узел ZeroTier (libzt, userspace) — работает БЕЗ VPN-слота Android,
 * поэтому не конфликтует с другими VPN (Hiddify и т.п.).
 *
 * Батарея: узел НЕ живёт постоянно. Он поднимается лениво при первой команде
 * ([acquire]) и гасится через [IDLE_STOP_MS] после последней ([release]).
 * Identity и кэши узла персистентны (filesDir/zt) — повторный старт быстрее
 * и node id стабилен (авторизуется на контроллере сети один раз).
 */
object ZtEmbedded {

    private const val TAG = "ZtEmbedded"
    private const val ONLINE_TIMEOUT_MS = 15_000L
    private const val NETWORK_TIMEOUT_MS = 15_000L

    // ponytail: 5 мин простоя до остановки узла. Холодный старт на LTE стоит
    // ~30-40 с (сходимость NAT-обхода через moon), поэтому в рамках сессии
    // пользования узел держим тёплым; батарею бережёт остановка после неё.
    private const val IDLE_STOP_MS = 300_000L

    private lateinit var storageDir: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var node: ZeroTierNode? = null
    private var joinedNetwork: Long = 0
    private var orbitedMoon: Long = 0
    private var idleStopJob: Job? = null

    /** Вызывается один раз из Application.onCreate. Узел НЕ запускается. */
    fun init(context: Context) {
        storageDir = File(context.filesDir, "zt")
    }

    /**
     * Node id встроенного узла (10 hex) для авторизации на контроллере сети,
     * или null, пока identity ещё не создана (создаётся при первой команде).
     */
    fun nodeId(): String? {
        val identity = File(storageDir, "identity.public")
        if (!identity.isFile) return null
        return identity.readText().substringBefore(':').ifEmpty { null }
    }

    /**
     * Поднять узел и дождаться готовности транспорта в сети [networkIdHex].
     * Возвращает ошибку текстом или null при успехе.
     */
    suspend fun acquire(
        networkIdHex: String, moonIdHex: String = "", rootsB64: String = "",
    ): String? = withContext(Dispatchers.IO) {
        val networkId = try {
            java.lang.Long.parseUnsignedLong(networkIdHex, 16)
        } catch (e: NumberFormatException) {
            return@withContext "плохой id сети ZeroTier: $networkIdHex"
        }
        mutex.withLock {
            idleStopJob?.cancel()
            val t0 = System.currentTimeMillis()
            val n = node ?: ZeroTierNode().also {
                storageDir.mkdirs()
                installRoots(rootsB64)
                Log.i(TAG, "init: storage=$storageDir")
                it.initFromStorage(storageDir.absolutePath)
                // Кэши ускоряют повторный холодный старт (меньше радио = батарея).
                // Кэш ПИРОВ выключен: после смены сети (Wi-Fi→LTE) протухшие
                // пути из него задерживают пересбор маршрута на десятки секунд.
                it.initAllowPeerCache(false)
                it.initAllowNetworkCache(true)
                it.initAllowRootsCache(true)
                it.initAllowIdCache(true)
                Log.i(TAG, "start()…")
                it.start()
                node = it
            }
            if (!waitFor(ONLINE_TIMEOUT_MS) { n.isOnline }) {
                return@withLock "узел ZeroTier не вышел в онлайн (нет связи с корнями?)"
            }
            Log.i(TAG, "online за ${System.currentTimeMillis() - t0} мс")
            // Собственный корень сети (moon): без него узлы за блокировками
            // публичных planet-корней не находят друг друга (rendezvous).
            // ВАЖНО: только после онлайна — start() асинхронный, а orbit в libzt
            // не проверяет готовность нативного узла (SIGSEGV при раннем вызове).
            if (orbitedMoon == 0L) {
                moonIdHex.toULongOrNull(16)?.toLong()?.let { moonId ->
                    val rc = ZeroTierNative.zts_moon_orbit(moonId, moonId)
                    Log.i(TAG, "orbit($moonIdHex) rc=$rc")
                    orbitedMoon = moonId
                }
            }
            if (joinedNetwork != networkId) {
                Log.i(TAG, "join($networkIdHex)…")
                n.join(networkId)
                joinedNetwork = networkId
            }
            if (!waitFor(NETWORK_TIMEOUT_MS) { n.isNetworkTransportReady(networkId) }) {
                return@withLock "сеть ZeroTier не готова — узел ${nodeId() ?: "?"} " +
                    "авторизован на контроллере?"
            }
            Log.i(TAG, "transport ready за ${System.currentTimeMillis() - t0} мс, " +
                "ip=${runCatching { n.getIPv4Address(networkId) }.getOrNull()}")
            null
        }
    }

    /** Отметить конец работы: узел погаснет через [IDLE_STOP_MS] простоя. */
    fun release() {
        idleStopJob?.cancel()
        idleStopJob = scope.launch {
            delay(IDLE_STOP_MS)
            mutex.withLock {
                node?.let {
                    Log.i(TAG, "Останавливаю встроенный узел ZeroTier (простой)")
                    // libzt: JNI-обёртка zts_node_stop делает DetachCurrentThread на
                    // Java-потоке → SIGABRT. AAR пропатчен (tools/patch-libzt-detach.py);
                    // при обновлении AAR прогнать скрипт заново.
                    it.stop()
                }
                node = null
                joinedNetwork = 0
                orbitedMoon = 0
            }
        }
    }

    /**
     * Кастомный корневой мир (planet) из QR-профиля: World, чей корень —
     * собственный moon-сервер сети. Кладётся в storage как кэш `roots`
     * ДО старта узла; мир с чужим world id не затирается дефолтным planet.
     * Без него узлы за блокировками публичных корней не проходят rendezvous.
     */
    private fun installRoots(rootsB64: String) {
        if (rootsB64.isEmpty()) return
        runCatching {
            val blob = android.util.Base64.decode(rootsB64, android.util.Base64.DEFAULT)
            val target = File(storageDir, "roots")
            if (!target.isFile || !target.readBytes().contentEquals(blob)) {
                target.writeBytes(blob)
                Log.i(TAG, "установлен кастомный planet (${blob.size} байт)")
            }
        }.onFailure { Log.w(TAG, "не удалось установить planet: ${it.message}") }
    }

    private suspend fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            delay(250)
        }
        return condition()
    }
}
