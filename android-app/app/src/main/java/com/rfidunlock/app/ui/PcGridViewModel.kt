package com.rfidunlock.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rfidunlock.app.data.PcProfile
import com.rfidunlock.app.data.PcProfileRepository
import com.rfidunlock.app.data.PcStatus
import com.rfidunlock.app.net.CommandResult
import com.rfidunlock.app.net.TcpCommandClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Модель экрана плиток ПК.
 *
 * Статус запрашивается по требованию (при открытии экрана и по кнопке «Обновить»),
 * без фонового опроса — как решено по требованию №3.
 */
class PcGridViewModel(
    private val repository: PcProfileRepository,
    private val client: TcpCommandClient = TcpCommandClient(),
) : ViewModel() {

    val profiles: StateFlow<List<PcProfile>> = repository.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _statuses = MutableStateFlow<Map<String, PcStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, PcStatus>> = _statuses.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    // Профили с командой в полёте: повторный тап по плитке игнорируется,
    // иначе дабл-тап успевает отправить две команды (вплоть до противоположных).
    private val togglesInFlight = mutableSetOf<String>()

    /** Запросить статус всех профилей. */
    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            val current = profiles.value
            current.forEach { setStatus(it.id, queryStatus(it)) }
            _refreshing.value = false
        }
    }

    /** Переключить состояние ПК: заблокирован → разблокировать, иначе заблокировать. */
    fun toggle(profile: PcProfile) {
        if (!togglesInFlight.add(profile.id)) return
        viewModelScope.launch {
            try {
                // Всегда запрашиваем актуальный статус перед действием — кэш может устареть.
                val status = queryStatus(profile)
                setStatus(profile.id, status)
                val result: CommandResult = when (status) {
                    PcStatus.LOCKED -> client.unlock(profile.toServerSettings())
                    PcStatus.UNLOCKED -> client.lock(profile.toServerSettings())
                    else -> return@launch // OFFLINE/UNKNOWN — переключать нечего
                }
                if (result.ok) {
                    setStatus(profile.id, queryStatus(profile))
                    // Разовое обновление статуса через 2 c: ПК мог завершить переход
                    // (особенно разблокировку) с задержкой относительно ответа на команду.
                    delay(2000)
                    setStatus(profile.id, queryStatus(profile))
                } else {
                    setStatus(profile.id, PcStatus.OFFLINE)
                }
            } finally {
                togglesInFlight.remove(profile.id)
            }
        }
    }

    private suspend fun queryStatus(profile: PcProfile): PcStatus {
        val result = client.status(profile.toServerSettings())
        if (!result.ok) return PcStatus.OFFLINE
        // detail вида "locked=<LockedHint>", где LockedHint = yes/no (loginctl).
        val detail = result.detail.lowercase()
        return when {
            detail.contains("locked=yes") || detail.contains("locked=true") -> PcStatus.LOCKED
            detail.contains("locked=no") || detail.contains("locked=false") -> PcStatus.UNLOCKED
            else -> PcStatus.UNKNOWN
        }
    }

    private fun setStatus(id: String, status: PcStatus) {
        _statuses.update { it + (id to status) }
    }

    /** LOCK при отключении зарядки — настройка конкретного ПК. */
    fun setLockOnPowerDisconnect(profile: PcProfile, enabled: Boolean) {
        viewModelScope.launch { repository.setLockOnPowerDisconnect(profile, enabled) }
    }

    /** Удалить профиль ПК. */
    fun delete(profile: PcProfile) {
        viewModelScope.launch {
            repository.delete(profile)
            _statuses.update { it - profile.id }
        }
    }

    class Factory(private val repository: PcProfileRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PcGridViewModel(repository) as T
        }
    }
}
