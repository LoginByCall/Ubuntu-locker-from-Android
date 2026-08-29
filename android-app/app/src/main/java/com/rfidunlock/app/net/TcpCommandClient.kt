package com.rfidunlock.app.net

import android.util.Log
import com.rfidunlock.app.data.ServerSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
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
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(settings.host, settings.port), connectTimeoutMs)
                    socket.soTimeout = readTimeoutMs
                    writeLine(socket.getOutputStream(), request)
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    val responseLine = reader.readLine()
                        ?: return@use CommandResult(false, "нет ответа")
                    val json = JSONObject(responseLine)
                    val ok = json.optString("status") == "ok"
                    val detail = json.optString("detail")
                    Log.i(tag, "$cmd reqId=$reqId -> ${json.optString("status")} ($detail)")
                    CommandResult(ok, detail)
                }
            }.getOrElse { e ->
                Log.w(tag, "Ошибка отправки $cmd: ${e.message}")
                CommandResult(false, e.message ?: "ошибка сети")
            }
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
