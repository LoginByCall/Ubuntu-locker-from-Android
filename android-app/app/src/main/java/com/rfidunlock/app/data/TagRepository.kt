package com.rfidunlock.app.data

import kotlinx.coroutines.flow.Flow

/** Репозиторий доступа к меткам. */
class TagRepository(private val dao: TagDao) {

    val tags: Flow<List<Tag>> = dao.observeAll()

    suspend fun findByUid(uid: String): Tag? = dao.findByUid(uid)

    /**
     * Сохранить новую метку или обновить имя существующей (защита от дублей по UID).
     */
    suspend fun registerOrRename(uid: String, name: String) {
        val existing = dao.findByUid(uid)
        if (existing == null) {
            dao.upsert(Tag(uid = uid, name = name))
        } else {
            dao.update(existing.copy(name = name, updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun setEnabled(tag: Tag, enabled: Boolean) {
        dao.update(tag.copy(enabled = enabled, updatedAt = System.currentTimeMillis()))
    }

    /** Сменить логику срабатывания метки. */
    suspend fun setMode(tag: Tag, mode: TagMode) {
        dao.update(tag.copy(mode = mode, updatedAt = System.currentTimeMillis()))
    }

    /** Зафиксировать следующее действие для режима TOGGLE. */
    suspend fun setToggleNextLock(uid: String, nextLock: Boolean) {
        val existing = dao.findByUid(uid) ?: return
        dao.update(existing.copy(toggleNextLock = nextLock, updatedAt = System.currentTimeMillis()))
    }

    /** Привязать метку к профилю ПК (null — «универсальная»). */
    suspend fun setProfile(tag: Tag, profileId: String?) {
        dao.update(tag.copy(profileId = profileId, updatedAt = System.currentTimeMillis()))
    }

    suspend fun delete(tag: Tag) = dao.delete(tag)
}
