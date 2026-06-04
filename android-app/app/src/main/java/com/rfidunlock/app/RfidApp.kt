package com.rfidunlock.app

import android.app.Application
import com.rfidunlock.app.data.AppDatabase
import com.rfidunlock.app.data.PcProfile
import com.rfidunlock.app.data.PcProfileRepository
import com.rfidunlock.app.data.SettingsRepository
import com.rfidunlock.app.data.TagRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

class RfidApp : Application() {
    lateinit var tagRepository: TagRepository
        private set
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var pcProfileRepository: PcProfileRepository
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.get(this)
        tagRepository = TagRepository(db.tagDao())
        settingsRepository = SettingsRepository(this)
        pcProfileRepository = PcProfileRepository(db.pcProfileDao())
        migrateLegacySettings()
    }

    /**
     * Одноразовый перенос единственного ПК из старых настроек (DataStore) в
     * первый профиль, если профилей ещё нет, а адрес ПК был задан.
     */
    private fun migrateLegacySettings() {
        appScope.launch {
            if (pcProfileRepository.count() > 0) return@launch
            val legacy = settingsRepository.settings.first()
            if (!legacy.isConfigured) return@launch
            pcProfileRepository.save(
                PcProfile(
                    id = UUID.randomUUID().toString(),
                    name = legacy.host,
                    host = legacy.host,
                    port = legacy.port,
                    token = legacy.token,
                )
            )
        }
    }
}
