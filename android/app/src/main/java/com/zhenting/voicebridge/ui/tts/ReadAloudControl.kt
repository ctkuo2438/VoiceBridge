package com.zhenting.voicebridge.ui.tts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zhenting.voicebridge.R
import com.zhenting.voicebridge.data.model.TtsIssue
import com.zhenting.voicebridge.ui.components.AppSymbol
import com.zhenting.voicebridge.ui.components.SymbolIcon

@Composable
fun ReadAloudControl(
    itemId: String,
    text: String,
    state: TtsUiState,
    onRead: (String, String) -> Unit,
    onStop: () -> Unit,
    enabled: Boolean = true
) {
    val selected = state.itemId == itemId
    val active = selected && state.isActive
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { if (active) onStop() else onRead(itemId, text) },
                enabled = active || enabled,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.testTag(if (active) "tts_stop_$itemId" else "tts_play_$itemId")) {
                SymbolIcon(if (active) AppSymbol.Stop else AppSymbol.Speaker, Modifier.size(16.dp))
                androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
                Text(stringResource(when {
                    !active -> R.string.tts_read
                    state.phase == TtsPhase.Playing -> R.string.tts_stop
                    else -> R.string.tts_cancel
                }))
            }
            if (active) {
                if (state.phase != TtsPhase.Playing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(stringResource(when (state.phase) {
                    TtsPhase.Generating -> R.string.tts_generating
                    TtsPhase.Preparing -> R.string.tts_preparing
                    else -> R.string.tts_playing
                }), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f).testTag("tts_status_$itemId")
                        .semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
        if (active && state.totalParts > 1) Text(
            stringResource(R.string.tts_part, state.part, state.totalParts),
            style = MaterialTheme.typography.bodySmall)
        if (selected) state.error?.let { issue ->
            Text(stringResource(when (issue) {
                TtsIssue.InvalidText -> R.string.tts_invalid_text
                TtsIssue.Network -> R.string.request_network
                TtsIssue.Timeout -> R.string.request_timeout
                TtsIssue.SessionExpired -> R.string.tts_session_expired
                TtsIssue.Unavailable -> R.string.tts_unavailable
                TtsIssue.RateLimited -> R.string.tts_rate_limited
                TtsIssue.InvalidAudio -> R.string.tts_invalid_audio
                TtsIssue.PlaybackUnavailable -> R.string.tts_playback_unavailable
                TtsIssue.Interrupted -> R.string.tts_interrupted
            }), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("tts_error_$itemId").semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}
