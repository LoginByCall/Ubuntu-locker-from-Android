package com.rfidunlock.app.net

import android.content.Context
import android.util.Log
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

    // ponytail: 60 с простоя до остановки узла; если холодный старт в реальной
    // сети окажется дорогим — поднять окно или добавить предпрогрев при
    // открытии приложения.
    private const val IDLE_STOP_MS = 60_000L

    private lateinit var storageDir: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var node: ZeroTierNode? = null
    private var joinedNetwork: Long = 0
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
    suspend fun acquire(networkIdHex: String): String? = withContext(Dispatchers.IO) {
        val networkId = try {
            java.lang.Long.parseUnsignedLong(networkIdHex, 16)
        } catch (e: NumberFormatException) {
            return@withContext "плохой id сети ZeroTier: $networkIdHex"
        }
        mutex.withLock {
            idleStopJob?.cancel()
            val n = node ?: ZeroTierNode().also {
                storageDir.mkdirs()
                it.initFromStorage(storageDir.absolutePath)
                // Кэши ускоряют повторный холодный старт (меньше радио = батарея)
                it.initAllowPeerCache(true)
                it.initAllowNetworkCache(true)
                it.initAllowRootsCache(true)
                it.initAllowIdCache(true)
                it.start()
                node = it
            }
            if (!waitFor(ONLINE_TIMEOUT_MS) { n.isOnline }) {
                return@withLock "узел ZeroTier не вышел в онлайн (нет связи с корнями?)"
            }
            if (joinedNetwork != networkId) {
                n.join(networkId)
                joinedNetwork = networkId
            }
            if (!waitFor(NETWORK_TIMEOUT_MS) { n.isNetworkTransportReady(networkId) }) {
                return@withLock "сеть ZeroTier не готова — узел ${nodeId() ?: "?"} " +
                    "авторизован на контроллере?"
            }
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
                    it.stop()
                }
                node = null
                joinedNetwork = 0
            }
        }
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
