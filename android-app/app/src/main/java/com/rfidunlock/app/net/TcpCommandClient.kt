package com.rfidunlock.app.net

import android.util.Log
import com.rfidunlock.app.data.ServerSettings
import com.zerotier.sockets.ZeroTierSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Результат выполнения команды на ПК. */
data class CommandResult(
    val ok: Boolean,
    val detail: String,
    /** Текущий LAN-адрес ПК из ответа на status (пусто, если сервер не прислал). */
    val lan: String = "",
)

/**
 * Простой TCP-клиент собственного канала ПК↔телефон.
 *
 * Протокол: построчный JSON. На каждое соединение отправляется одна команда,
 * читается один ответ, соединение закрывается. Это надёжнее держания
 * постоянного сокета при нестабильном Wi-Fi и проще для повторов.
 *
 * Аутентификация (этап 2, ТЗ 7.2): токен по сети не передаётся — команда
 * подписывается HMAC-SHA256(token, "cmd|reqId|ts"), сервер проверяет окно
 * времени и уникальность reqId (анти-replay).
 * БЕЗОПАСНОСТЬ: криптографическая часть требует ревью человеком.
 */
class TcpCommandClient {

    private val tag = "TcpCommandClient"
    private val connectTimeoutMs = 3000
    private val readTimeoutMs = 5000

    // Быстрая проба LAN-адреса. Если ПК в той же сети — ответ за десятки мс;
    // если нет — сокет отваливается по таймауту (или сразу ENETUNREACH),
    // и дальше идёт обычный путь. Цена ошибки — эти 400 мс.
    private val lanConnectTimeoutMs = 400

    suspend fun lock(settings: ServerSettings): CommandResult = send("lock", settings)

    suspend fun unlock(settings: ServerSettings): CommandResult = send("unlock", settings)

    suspend fun status(settings: ServerSettings): CommandResult = send("status", settings)

    /** Вердикт по запросу подтверждения (askId пришёл в push с ПК). */
    suspend fun confirm(settings: ServerSettings, askId: String, approve: Boolean): CommandResult =
        send("confirm", settings, mapOf("askId" to askId,
            "verdict" to if (approve) "approve" else "deny"))

    /** Сообщить ПК push-токен телефона, чтобы он мог запрашивать подтверждения. */
    suspend fun register(settings: ServerSettings, fcmToken: String): CommandResult =
        send("register", settings, mapOf("fcm" to fcmToken))

    /**
     * Поля тела, входящие в подпись помимо "cmd|reqId|ts" — иначе вердикт
     * можно было бы подменить в пути (канал без TLS). Порядок важен и должен
     * совпадать с SIGNED_FIELDS в rfid-server.py.
     */
    private val signedFields = mapOf(
        "confirm" to listOf("askId", "verdict"),
        "register" to listOf("fcm"),
    )

    private suspend fun send(
        cmd: String, settings: ServerSettings, body: Map<String, String> = emptyMap(),
    ): CommandResult =
        withContext(Dispatchers.IO) {
            if (!settings.isConfigured) {
                return@withContext CommandResult(false, "ПК не настроен")
            }
            val reqId = UUID.randomUUID().toString()
            val ts = System.currentTimeMillis() / 1000
            val signed = (listOf(cmd, reqId, ts.toString()) +
                signedFields[cmd].orEmpty().map { body[it] ?: "" }).joinToString("|")
            val request = JSONObject().apply {
                put("cmd", cmd)
                put("reqId", reqId)
                put("ts", ts)
                put("sig", sign(signed, settings.token))
                body.forEach { (key, value) -> put(key, value) }
            }.toString()

            sendViaLan(settings, cmd, reqId, request)?.let { return@withContext it }

            runCatching {
                val embedded = useEmbeddedZt(settings)
                Log.i(tag, "$cmd -> ${settings.host} (транспорт: ${if (embedded) "libzt" else "direct"})")
                if (embedded) sendViaEmbeddedZt(settings, cmd, reqId, request)
                else sendDirect(settings, cmd, reqId, request)
            }.getOrElse { e ->
                Log.w(tag, "Ошибка отправки $cmd: ${e.message}")
                CommandResult(false, e.message ?: "ошибка сети")
            }
        }

    /**
     * Попытка по локальному адресу ПК. null = LAN-путь не сработал
     * (адреса нет, он совпадает с основным или ПК в этой сети не отвечает).
     */
    private fun sendViaLan(
        settings: ServerSettings, cmd: String, reqId: String, request: String,
    ): CommandResult? {
        val lan = settings.lan
        if (lan.isBlank() || lan == settings.host) return null
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(lan, settings.port), lanConnectTimeoutMs)
                socket.soTimeout = readTimeoutMs
                Log.i(tag, "$cmd -> $lan (транспорт: lan)")
                exchange(socket.getInputStream(), socket.getOutputStream(), cmd, reqId, request)
            }
        }.getOrElse {
            Log.i(tag, "LAN-путь недоступен ($lan): ${it.message}")
            null
        }
    }

    /**
     * Встроенный узел ZeroTier нужен, когда у профиля задана сеть, а системного
     * маршрута до ПК нет (официальный ZT-туннель выключен/вытеснен другим VPN).
     */
    private fun useEmbeddedZt(settings: ServerSettings): Boolean =
        settings.ztNetworkId.isNotBlank() && !hasLocalRouteTo(settings.host)

    /** Есть ли у устройства интерфейс в той же IPv4-подсети /24, что и ПК. */
    private fun hasLocalRouteTo(host: String): Boolean {
        val prefix = host.substringBeforeLast('.', "")
        if (prefix.isEmpty() || ":" in host) return true // IPv6 — пробуем напрямую
        return runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp }
                .flatMap { it.inetAddresses.asSequence() }
                .any { it.hostAddress?.startsWith("$prefix.") == true }
        }.getOrDefault(false)
    }

    private fun sendDirect(
        settings: ServerSettings, cmd: String, reqId: String, request: String,
    ): CommandResult =
        Socket().use { socket ->
            socket.connect(InetSocketAddress(settings.host, settings.port), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            exchange(socket.getInputStream(), socket.getOutputStream(), cmd, reqId, request)
        }

    private suspend fun sendViaEmbeddedZt(
        settings: ServerSettings, cmd: String, reqId: String, request: String,
    ): CommandResult {
        ZtEmbedded.acquire(settings.ztNetworkId, settings.ztMoonId, settings.ztRoots)
            ?.let { error ->
            Log.w(tag, "Встроенный ZeroTier: $error")
            return CommandResult(false, error)
        }
        try {
            // Первая попытка после смены сети может упереться в ещё не
            // пересобранный маршрут: сам connect запускает rendezvous,
            // поэтому одна повторная попытка обычно проходит.
            var lastError: Exception? = null
            repeat(2) { attempt ->
                try {
                    val socket = ZeroTierSocket(settings.host, settings.port)
                    try {
                        socket.setSoTimeout(readTimeoutMs)
                        return exchange(socket.inputStream, socket.outputStream, cmd, reqId, request)
                    } finally {
                        socket.close()
                    }
                } catch (e: java.io.IOException) {
                    lastError = e
                    Log.w(tag, "connect через libzt (попытка ${attempt + 1}): ${e.message}")
                }
            }
            throw lastError!!
        } finally {
            ZtEmbedded.release() // узел погаснет после простоя — экономия батареи
        }
    }

    private fun exchange(
        input: InputStream, output: OutputStream, cmd: String, reqId: String, request: String,
    ): CommandResult {
        writeLine(output, request)
        val reader = BufferedReader(InputStreamReader(input))
        val responseLine = reader.readLine() ?: return CommandResult(false, "нет ответа")
        val json = JSONObject(responseLine)
        val ok = json.optString("status") == "ok"
        val detail = json.optString("detail")
        Log.i(tag, "$cmd reqId=$reqId -> ${json.optString("status")} ($detail)")
        return CommandResult(ok, detail, json.optString("lan").trim())
    }

    private fun sign(message: String, token: String): String {
        if (token.isEmpty()) return "" // сервер без токена подпись не проверяет
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun writeLine(out: OutputStream, line: String) {
        out.write((line + "\n").toByteArray(Charsets.UTF_8))
        out.flush()
    }
}
