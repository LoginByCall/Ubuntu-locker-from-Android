package com.rfidunlock.app.kdeconnect

import org.json.JSONObject

/**
 * Пакет протокола KDE Connect (одна строка JSON, завершённая '\n').
 *
 * Формат:
 * {
 *   "id": <millis>,
 *   "type": "kdeconnect.<plugin>",
 *   "body": { ... }
 * }
 */
object NetworkPacket {

    const val TYPE_IDENTITY = "kdeconnect.identity"
    const val TYPE_PAIR = "kdeconnect.pair"
    const val TYPE_PING = "kdeconnect.ping"
    const val TYPE_RUNCOMMAND_REQUEST = "kdeconnect.runcommand.request"

    const val PROTOCOL_VERSION = 7
    const val DEFAULT_PORT = 1716

    fun create(type: String, body: JSONObject): JSONObject =
        JSONObject().apply {
            put("id", System.currentTimeMillis())
            put("type", type)
            put("body", body)
        }

    /** Identity-пакет, описывающий это устройство. */
    fun identity(
        deviceId: String,
        deviceName: String,
        tcpPort: Int? = null,
    ): JSONObject {
        val body = JSONObject().apply {
            put("deviceId", deviceId)
            put("deviceName", deviceName)
            put("deviceType", "phone")
            put("protocolVersion", PROTOCOL_VERSION)
            put("incomingCapabilities", org.json.JSONArray(listOf(TYPE_PING)))
            put(
                "outgoingCapabilities",
                org.json.JSONArray(listOf(TYPE_PING, TYPE_RUNCOMMAND_REQUEST))
            )
            if (tcpPort != null) put("tcpPort", tcpPort)
        }
        return create(TYPE_IDENTITY, body)
    }

    /** Pair-пакет (запрос или подтверждение сопряжения). */
    fun pair(pair: Boolean): JSONObject =
        create(TYPE_PAIR, JSONObject().put("pair", pair))

    /** Запрос на выполнение команды Run Command по её ключу в GSConnect. */
    fun runCommand(commandKey: String): JSONObject =
        create(TYPE_RUNCOMMAND_REQUEST, JSONObject().put("key", commandKey))

    fun serialize(packet: JSONObject): ByteArray =
        (packet.toString() + "\n").toByteArray(Charsets.UTF_8)
}
