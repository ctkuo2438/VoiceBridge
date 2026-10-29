package com.zhenting.voicebridge.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhenting.voicebridge.data.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class UserWorkspace(val userId: String) : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

/** Retains drafts across rotation, and cancels/clears them when the user signs out. */
class WorkspaceViewModel(repository: AuthRepository) : ViewModel() {
    private val _workspace = MutableStateFlow<UserWorkspace?>(null)
    val workspace = _workspace.asStateFlow()

    init {
        viewModelScope.launch {
            repository.user.map { it?.id }.distinctUntilChanged().collect { userId ->
                val previous = _workspace.value
                _workspace.value = null
                previous?.viewModelStore?.clear()
                _workspace.value = userId?.let { UserWorkspace(it) }
            }
        }
    }

    override fun onCleared() { _workspace.value?.viewModelStore?.clear() }

    companion object {
        fun factory(repository: AuthRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { WorkspaceViewModel(repository) }
        }
    }
}
