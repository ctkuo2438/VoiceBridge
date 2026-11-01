package com.zhenting.voicebridge.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhenting.voicebridge.data.repository.AuthRepository
import com.zhenting.voicebridge.data.local.RecoveryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class UserWorkspace(val userId: String, val recovery: RecoveryStore = RecoveryStore()) : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

/** Clears in-memory UI on sign-out; account-scoped local recovery remains on this device. */
class WorkspaceViewModel(
    private val repository: AuthRepository,
    private val loadRecovery: suspend (String) -> RecoveryStore = { RecoveryStore() }
) : ViewModel() {
    private val _workspace = MutableStateFlow<UserWorkspace?>(null)
    val workspace = _workspace.asStateFlow()
    private val _storageError = MutableStateFlow(false)
    val storageError = _storageError.asStateFlow()

    init {
        viewModelScope.launch {
            repository.user.map { it?.id }.distinctUntilChanged().collectLatest { userId ->
                val previous = _workspace.value
                _workspace.value = null
                previous?.viewModelStore?.clear()
                _storageError.value = false
                if (userId != null) restore(userId)
            }
        }
    }

    private suspend fun restore(userId: String) {
        try {
            val recovery = loadRecovery(userId)
            if (repository.user.value?.id == userId && _workspace.value == null) {
                _workspace.value = UserWorkspace(userId, recovery)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            if (repository.user.value?.id == userId && _workspace.value == null) _storageError.value = true
        }
    }

    fun retryRestore() {
        if (!_storageError.value) return
        _storageError.value = false
        repository.user.value?.id?.let { id -> viewModelScope.launch { restore(id) } }
    }

    override fun onCleared() { _workspace.value?.viewModelStore?.clear() }

    companion object {
        fun factory(repository: AuthRepository, loadRecovery: suspend (String) -> RecoveryStore = { RecoveryStore() }): ViewModelProvider.Factory = viewModelFactory {
            initializer { WorkspaceViewModel(repository, loadRecovery) }
        }
    }
}
