package com.rfidunlock.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Профиль ПК — целевое устройство для команд LOCK/UNLOCK.
 *
 * Создаётся при сканировании QR-кода, показанного агентом на ПК.
 *
 * @param id стабильный идентификатор профиля (UUID из QR), первичный ключ.
 * @param name удобочитаемое имя ПК (hostname из QR).
 * @param host основной адрес ПК (ZeroTier/Yggdrasil или LAN — что нашёл агент).
 * @param lan адрес ПК в локальной сети; пробуется первым и обновляется из
 *   ответа на status (DHCP). Пусто = только [host].
 * @param port TCP-порт агента.
 * @param token предварительный токен для аутентификации команд.
 * @param os семейство ОС ПК (ubuntu/linux/windows/macos) для иконки на плитке.
 * @param ztNetworkId id сети ZeroTier (16 hex-символов) для встроенного узла
 *   (libzt) — используется, когда системного ZT-туннеля нет; пусто = только
 *   прямое подключение.
 * @param lockOnPowerDisconnect слать LOCK этому ПК при отключении телефона от
 *   зарядки (снятие с Type-C дока) — независимо от меток.
 * @param createdAt время добавления (epoch millis).
 * @param updatedAt время последнего изменения (epoch millis).
 */
@Entity(tableName = "pc_profiles")
data class PcProfile(
    @PrimaryKey val id: String,
    val name: String,
    val host: String,
    val lan: String = "",
    val port: Int = 5390,
    val token: String = "",
    val os: String = "",
    val ztNetworkId: String = "",
    val ztMoonId: String = "",
    val ztRoots: String = "",
    val lockOnPowerDisconnect: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    /** Транспортные настройки для [com.rfidunlock.app.net.TcpCommandClient]. */
    fun toServerSettings(): ServerSettings = ServerSettings(
        host = host, lan = lan, port = port, token = token,
        ztNetworkId = ztNetworkId, ztMoonId = ztMoonId, ztRoots = ztRoots,
    )

    /** Адрес для отображения: IPv6-литерал (Yggdrasil) — в квадратных скобках. */
    fun hostPort(): String = if (":" in host) "[$host]:$port" else "$host:$port"
}
