package com.zhenting.voicebridge.ui.connection

import com.zhenting.voicebridge.data.repository.HealthCheckResult

data class ConnectionUiState(
    val isChecking: Boolean = false,
    val result: HealthCheckResult? = null
)
