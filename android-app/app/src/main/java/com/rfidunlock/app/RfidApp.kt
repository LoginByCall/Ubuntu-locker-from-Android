package com.rfidunlock.app

import android.app.Application
import com.rfidunlock.app.data.AppDatabase
import com.rfidunlock.app.data.SettingsRepository
import com.rfidunlock.app.data.TagRepository

class RfidApp : Application() {
    lateinit var tagRepository: TagRepository
        private set
    lateinit var settingsRepository: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.get(this)
        tagRepository = TagRepository(db.tagDao())
        settingsRepository = SettingsRepository(this)
    }
}
