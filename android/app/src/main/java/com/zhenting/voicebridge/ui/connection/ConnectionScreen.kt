package com.zhenting.voicebridge.ui.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhenting.voicebridge.R
import com.zhenting.voicebridge.data.repository.HealthCheckResult
import com.zhenting.voicebridge.ui.theme.VoiceBridgeTheme

@Composable
fun ConnectionRoute(
    viewModel: ConnectionViewModel,
    baseUrl: String,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ConnectionScreen(uiState, baseUrl, viewModel::checkConnection, modifier)
}

@Composable
fun ConnectionScreen(
    uiState: ConnectionUiState,
    baseUrl: String,
    onCheckConnection: () -> Unit,
    modifier: Modifier = Modifier
) {
    val title = when {
        uiState.isChecking -> R.string.connection_checking
        uiState.result == HealthCheckResult.Healthy -> R.string.connection_connected
        uiState.result == null -> R.string.connection_ready
        else -> R.string.connection_failed
    }
    val message = when (val result = uiState.result) {
        HealthCheckResult.Healthy -> stringResource(R.string.connection_success)
        HealthCheckResult.Unreachable -> stringResource(R.string.connection_unreachable)
        HealthCheckResult.InvalidResponse -> stringResource(R.string.connection_invalid_response)
        is HealthCheckResult.HttpError -> stringResource(
            R.string.connection_http_error, result.statusCode
        )
        null -> stringResource(
            if (uiState.isChecking) R.string.connection_waiting else R.string.connection_instructions
        )
    }
    val failed = uiState.result != null && uiState.result != HealthCheckResult.Healthy

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
        Text(
            stringResource(R.string.connection_description),
            style = MaterialTheme.typography.bodyLarge
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (failed) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    stringResource(title),
                    modifier = Modifier.testTag("connection_status").semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
                    style = MaterialTheme.typography.titleLarge
                )
                Text(message, modifier = Modifier.testTag("connection_message"))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.connection_server),
                style = MaterialTheme.typography.labelLarge
            )
            SelectionContainer {
                Text(
                    baseUrl,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        Button(
            onClick = onCheckConnection,
            enabled = !uiState.isChecking,
            modifier = Modifier.fillMaxWidth().testTag("check_connection_button")
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (uiState.isChecking) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                Text(
                    stringResource(
                        if (uiState.isChecking) R.string.connection_checking
                        else R.string.connection_test
                    )
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ConnectionScreenPreview() {
    VoiceBridgeTheme {
        ConnectionScreen(ConnectionUiState(), "http://10.0.2.2:5003/", {})
    }
}
