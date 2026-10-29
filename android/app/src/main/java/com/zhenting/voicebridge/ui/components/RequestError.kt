package com.zhenting.voicebridge.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.zhenting.voicebridge.R
import com.zhenting.voicebridge.data.remote.ApiIssue
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.ZoneId

@Composable
fun RequestError(issue: ApiIssue, modifier: Modifier = Modifier, uncertain: Boolean = false) {
    val resource = if (uncertain) R.string.request_uncertain else when (issue) {
        ApiIssue.InvalidInput -> R.string.request_invalid_input
        ApiIssue.SessionExpired -> R.string.auth_session_expired
        ApiIssue.NotFound -> R.string.request_not_found
        ApiIssue.Conflict -> R.string.request_conflict
        ApiIssue.Unavailable -> R.string.request_unavailable
        ApiIssue.Network -> R.string.request_network
        ApiIssue.Timeout -> R.string.request_timeout
        ApiIssue.InvalidResponse -> R.string.request_invalid_response
        ApiIssue.SaveUnconfirmed -> R.string.memo_save_unconfirmed
    }
    Text(stringResource(resource), modifier, color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium)
}

fun displayTimestamp(timestamp: String): String = try {
    OffsetDateTime.parse(timestamp).atZoneSameInstant(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))
} catch (_: java.time.DateTimeException) {
    timestamp
}
