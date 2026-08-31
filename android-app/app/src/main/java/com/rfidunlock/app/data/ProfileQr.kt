package com.rfidunlock.app.data

import org.json.JSONObject

/**
 * Разбор QR-кода профиля ПК, который показывает агент в системном трее.
 *
 * Формат полезной нагрузки (JSON):
 * ```
 * {"v":1,"id":"<uuid>","name":"<hostname>","host":"<ip>","lan":"<lan-ip>",
 *  "port":5390,"token":"<token>"}
 * ```
 */
object ProfileQr {

    /** Поддерживаемая версия формата QR. */
    const val VERSION = 1

    /** Результат разбора QR-кода. */
    sealed interface Result {
        data class Ok(val profile: PcProfile) : Result
        data class Error(val message: String) : Result
    }

    /**
     * Разобрать содержимое QR-кода в [PcProfile].
     *
     * Валидируется версия формата и обязательные поля (id, name, host, token).
     */
    fun parse(raw: String): Result {
        val json = try {
            JSONObject(raw.trim())
        } catch (e: Exception) {
            return Result.Error("Это не QR-код профиля ПК")
        }

        val version = json.optInt("v", -1)
        if (version != VERSION) {
            return Result.Error("Несовместимая версия QR-кода (v=$version)")
        }

        val id = json.optString("id").trim()
        val name = json.optString("name").trim()
        val host = json.optString("host").trim()
        val lan = json.optString("lan").trim()
        val port = json.optInt("port", 5390)
        val token = json.optString("token").trim()
        val os = json.optString("os").trim()
        val ztNetworkId = json.optString("ztnet").trim()
        val ztMoonId = json.optString("ztmoon").trim()
        val ztRoots = json.optString("ztroots").trim()

        if (id.isEmpty() || host.isEmpty() || token.isEmpty()) {
            return Result.Error("В QR-коде не хватает данных профиля")
        }

        return Result.Ok(
            PcProfile(
                id = id,
                name = name.ifEmpty { host },
                host = host,
                lan = lan,
                port = port,
                token = token,
                os = os,
                ztNetworkId = ztNetworkId,
                ztMoonId = ztMoonId,
                ztRoots = ztRoots,
            )
        )
    }
}
