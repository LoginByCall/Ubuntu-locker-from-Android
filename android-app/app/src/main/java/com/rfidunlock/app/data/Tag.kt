package com.rfidunlock.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Сохранённая NFC-метка.
 *
 * @param uid аппаратный идентификатор метки (hex-строка), первичный ключ.
 * @param name удобочитаемое имя, заданное пользователем.
 * @param enabled признак «активна»: если true — метка участвует в автоматизации.
 * @param createdAt время создания записи (epoch millis).
 * @param updatedAt время последнего изменения (epoch millis).
 */
@Entity(tableName = "tags")
data class Tag(
    @PrimaryKey val uid: String,
    val name: String,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
