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

    suspend fun lock(settings: ServerSettings): CommandResult = send("lock", settings)

    suspend fun unlock(settings: ServerSettings): CommandResult = send("unlock", settings)

    suspend fun status(settings: ServerSettings): CommandResult = send("status", settings)

    private suspend fun send(cmd: String, settings: ServerSettings): CommandResult =
        withContext(Dispatchers.IO) {
            if (!settings.isConfigured) {
                return@withContext CommandResult(false, "ПК не настроен")
            }
            val reqId = UUID.randomUUID().toString()
            val ts = System.currentTimeMillis() / 1000
            val request = JSONObject().apply {
                put("cmd", cmd)
                put("reqId", reqId)
                put("ts", ts)
                put("sig", sign("$cmd|$reqId|$ts", settings.token))
            }.toString()

            runCatching {
                if (useEmbeddedZt(settings)) sendViaEmbeddedZt(settings, cmd, reqId, request)
                else sendDirect(settings, cmd, reqId, request)
            }.getOrElse { e ->
                Log.w(tag, "Ошибка отправки $cmd: ${e.message}")
                CommandResult(false, e.message ?: "ошибка сети")
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
        ZtEmbedded.acquire(settings.ztNetworkId)?.let { error ->
            Log.w(tag, "Встроенный ZeroTier: $error")
            return CommandResult(false, error)
        }
        try {
            val socket = ZeroTierSocket(settings.host, settings.port)
            try {
                socket.setSoTimeout(readTimeoutMs)
                return exchange(socket.inputStream, socket.outputStream, cmd, reqId, request)
            } finally {
                socket.close()
            }
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
        return CommandResult(ok, detail)
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
