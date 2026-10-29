package com.zhenting.voicebridge.ui.speech

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zhenting.voicebridge.R
import com.zhenting.voicebridge.data.model.SpeechIssue

@Composable
fun SpeechInput(
    state: SpeechUiState,
    target: SpeechTarget,
    onStart: (SpeechTarget) -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onPermissionDenied: () -> Unit,
    onEditTranscript: (String) -> Unit,
    onUseTranscript: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var pendingTarget by remember { mutableStateOf<SpeechTarget?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val requested = pendingTarget
        pendingTarget = null
        if (requested != null && requested == target && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            if (granted) onStart(requested) else onPermissionDenied()
        }
    }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (state.phase) {
            SpeechPhase.Idle -> {
                OutlinedButton(onClick = {
                    keyboard?.hide()
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED) onStart(target)
                    else { pendingTarget = target; permission.launch(Manifest.permission.RECORD_AUDIO) }
                }, enabled = enabled, modifier = Modifier.testTag("speech_record_button")) {
                    Text(stringResource(R.string.speech_record))
                }
            }
            SpeechPhase.Recording -> {
                Text(stringResource(R.string.speech_recording, state.elapsedSeconds / 60, state.elapsedSeconds % 60),
                    modifier = Modifier.testTag("speech_recording"), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onStop, modifier = Modifier.testTag("speech_stop_button")) {
                        Text(stringResource(R.string.speech_stop))
                    }
                    TextButton(onCancel, modifier = Modifier.testTag("speech_cancel_button")) {
                        Text(stringResource(R.string.speech_cancel))
                    }
                }
            }
            SpeechPhase.Transcribing -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.speech_transcribing), modifier = Modifier.testTag("speech_transcribing"))
                TextButton(onCancel, modifier = Modifier.testTag("speech_cancel_button")) {
                    Text(stringResource(R.string.speech_cancel))
                }
            }
            SpeechPhase.Review -> Unit
        }
        state.error?.let { issue ->
            Text(stringResource(issue.messageResource()), color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("speech_error").semantics { liveRegion = LiveRegionMode.Polite })
            if (issue == SpeechIssue.PermissionDenied) TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")))
            }) { Text(stringResource(R.string.speech_settings)) }
        }
    }
    if (state.phase == SpeechPhase.Review) AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.speech_review)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.speech_review_description))
                OutlinedTextField(state.text, onEditTranscript, minLines = 3, maxLines = 8,
                    label = { Text(stringResource(R.string.speech_recognized_text)) },
                    modifier = Modifier.fillMaxWidth().testTag("speech_transcript_input"))
            }
        },
        confirmButton = {
            TextButton(onClick = { keyboard?.hide(); onUseTranscript() }, enabled = state.text.isNotBlank(),
                modifier = Modifier.testTag("speech_use_text_button")) {
                Text(stringResource(R.string.speech_use_text))
            }
        },
        dismissButton = { TextButton(onCancel) { Text(stringResource(R.string.speech_discard)) } }
    )
}

private fun SpeechIssue.messageResource(): Int = when (this) {
    SpeechIssue.PermissionDenied -> R.string.speech_permission_denied
    SpeechIssue.RecordingUnavailable -> R.string.speech_recording_unavailable
    SpeechIssue.Interrupted -> R.string.speech_interrupted
    SpeechIssue.NoAudio -> R.string.speech_no_audio
    SpeechIssue.TooLarge -> R.string.speech_too_large
    SpeechIssue.NoSpeech -> R.string.speech_no_text
    SpeechIssue.Network -> R.string.request_network
    SpeechIssue.Timeout -> R.string.request_timeout
    SpeechIssue.SessionExpired -> R.string.auth_session_expired
    SpeechIssue.Unavailable -> R.string.speech_unavailable
}
