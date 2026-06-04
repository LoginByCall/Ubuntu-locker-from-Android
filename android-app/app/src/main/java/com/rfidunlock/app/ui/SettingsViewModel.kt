package com.rfidunlock.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rfidunlock.app.data.PcProfile
import com.rfidunlock.app.data.PcProfileRepository
import com.rfidunlock.app.data.ServerSettings
import com.rfidunlock.app.data.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** ViewModel экрана настроек: профили ПК + ручное подключение (legacy). */
class SettingsViewModel(
    private val repository: SettingsRepository,
    private val pcProfileRepository: PcProfileRepository,
) : ViewModel() {

    val settings: StateFlow<ServerSettings> = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ServerSettings(),
    )

    val profiles: StateFlow<List<PcProfile>> = pcProfileRepository.profiles.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList(),
    )

    fun save(host: String, port: Int, token: String, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            repository.update(host, port, token)
            onSaved()
        }
    }

    /** Удалить профиль ПК. */
    fun deleteProfile(profile: PcProfile) {
        viewModelScope.launch {
            pcProfileRepository.delete(profile)
        }
    }

    class Factory(
        private val repository: SettingsRepository,
        private val pcProfileRepository: PcProfileRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(repository, pcProfileRepository) as T
    }
}
