package com.zhenting.voicebridge.ui.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhenting.voicebridge.data.repository.HealthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ConnectionViewModel(private val repository: HealthRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(ConnectionUiState())
    val uiState = _uiState.asStateFlow()

    fun checkConnection() {
        if (_uiState.value.isChecking) return
        _uiState.value = ConnectionUiState(isChecking = true)
        viewModelScope.launch {
            try {
                _uiState.value = ConnectionUiState(result = repository.checkConnection())
            } catch (cancelled: CancellationException) {
                _uiState.value = ConnectionUiState()
                throw cancelled
            }
        }
    }

    companion object {
        fun factory(repository: HealthRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { ConnectionViewModel(repository) }
        }
    }
}
