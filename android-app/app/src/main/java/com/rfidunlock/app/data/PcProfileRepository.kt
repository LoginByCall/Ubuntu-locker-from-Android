package com.rfidunlock.app.data

import kotlinx.coroutines.flow.Flow

/** Репозиторий доступа к профилям ПК. */
class PcProfileRepository(private val dao: PcProfileDao) {

    val profiles: Flow<List<PcProfile>> = dao.observeAll()

    suspend fun findById(id: String): PcProfile? = dao.findById(id)

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
                    port = profile.port,
                    token = profile.token,
                    os = profile.os,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    suspend fun delete(profile: PcProfile) = dao.delete(profile)
}
