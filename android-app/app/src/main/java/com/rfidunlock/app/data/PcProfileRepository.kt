package com.rfidunlock.app.data

import kotlinx.coroutines.flow.Flow

/** Репозиторий доступа к профилям ПК. */
class PcProfileRepository(private val dao: PcProfileDao) {

    val profiles: Flow<List<PcProfile>> = dao.observeAll()

    suspend fun findById(id: String): PcProfile? = dao.findById(id)

    /** Разовый снимок списка профилей (для фоновых задач без подписки). */
    suspend fun profilesOnce(): List<PcProfile> = dao.findAll()

    suspend fun count(): Int = dao.count()

    /** Сохранить новый профиль или обновить существующий по id (из QR). */
    suspend fun save(profile: PcProfile) {
        val existing = dao.findById(profile.id)
        if (existing == null) {
            dao.upsert(profile)
        } else {
            dao.update(
                existing.copy(
                    name = profile.name,
                    host = profile.host,
                    lan = profile.lan,
                    port = profile.port,
                    token = profile.token,
                    os = profile.os,
                    ztNetworkId = profile.ztNetworkId,
                    ztMoonId = profile.ztMoonId,
                    ztRoots = profile.ztRoots,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    /** Обновить адрес ПК в LAN (пришёл в ответе на status; меняется по DHCP). */
    suspend fun setLan(profile: PcProfile, lan: String) {
        if (lan.isBlank() || lan == profile.lan) return
        dao.update(profile.copy(lan = lan, updatedAt = System.currentTimeMillis()))
    }

    /** Запомнить режимы питания, о которых ПК сообщил в ответе на status. */
    suspend fun setPower(profile: PcProfile, power: String) {
        if (power == profile.power) return
        dao.update(profile.copy(power = power, updatedAt = System.currentTimeMillis()))
    }

    /** Включить/выключить LOCK этого ПК при отключении телефона от зарядки. */
    suspend fun setLockOnPowerDisconnect(profile: PcProfile, enabled: Boolean) =
        dao.update(profile.copy(lockOnPowerDisconnect = enabled, updatedAt = System.currentTimeMillis()))

    suspend fun delete(profile: PcProfile) = dao.delete(profile)
}
