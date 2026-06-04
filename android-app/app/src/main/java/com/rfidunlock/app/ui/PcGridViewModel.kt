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
        viewModelScope.launch {
            val status = _statuses.value[profile.id] ?: queryStatus(profile).also {
                setStatus(profile.id, it)
            }
            val result: CommandResult = when (status) {
                PcStatus.LOCKED -> client.unlock(profile.toServerSettings())
                PcStatus.UNLOCKED -> client.lock(profile.toServerSettings())
                else -> {
                    // Статус неизвестен/недоступен — повторно запросим.
                    setStatus(profile.id, queryStatus(profile))
                    return@launch
                }
            }
            if (result.ok) {
                setStatus(profile.id, queryStatus(profile))
            } else {
                setStatus(profile.id, PcStatus.OFFLINE)
            }
        }
    }

    private suspend fun queryStatus(profile: PcProfile): PcStatus {
        val result = client.status(profile.toServerSettings())
        if (!result.ok) return PcStatus.OFFLINE
        // detail вида "locked=true" / "locked=false"
        return when {
            result.detail.contains("locked=true", ignoreCase = true) -> PcStatus.LOCKED
            result.detail.contains("locked=false", ignoreCase = true) -> PcStatus.UNLOCKED
            else -> PcStatus.UNKNOWN
        }
    }

    private fun setStatus(id: String, status: PcStatus) {
        _statuses.update { it + (id to status) }
    }

    class Factory(private val repository: PcProfileRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PcGridViewModel(repository) as T
        }
    }
}
