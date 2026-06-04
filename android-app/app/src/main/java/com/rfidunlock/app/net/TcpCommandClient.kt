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
            val reqId = UUID.randomUUID().toString().take(8)
            val request = JSONObject().apply {
                put("cmd", cmd)
                put("reqId", reqId)
                put("token", settings.token)
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

    private fun writeLine(out: OutputStream, line: String) {
        out.write((line + "\n").toByteArray(Charsets.UTF_8))
        out.flush()
    }
}
