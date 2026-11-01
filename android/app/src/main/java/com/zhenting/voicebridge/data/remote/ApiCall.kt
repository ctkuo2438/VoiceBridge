package com.zhenting.voicebridge.data.remote

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.stream.MalformedJsonException
import com.zhenting.voicebridge.data.session.SessionChangedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import retrofit2.Response
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException

enum class ApiIssue {
    InvalidInput, SessionExpired, NotFound, Conflict, Unavailable,
    Network, Timeout, InvalidResponse, SaveUnconfirmed
}

class ApiException(val issue: ApiIssue, val outcomeUnknown: Boolean = false) : Exception(issue.name)

private val errorGson = Gson()

/** Never retry a POST automatically: a lost response may follow a successful write. */
suspend fun <T : Any> apiCall(mutation: Boolean = false, call: suspend () -> Response<T>): T = try {
    val response = call()
    if (!response.isSuccessful) {
        val code = response.errorBody()?.use {
            try {
                errorGson.fromJson(it.charStream(), ApiErrorResponse::class.java)?.code
            } catch (_: JsonParseException) {
                null
            }
        }
        val issue = when {
            response.code() == 401 -> ApiIssue.SessionExpired
            code == "conversation_not_found" -> ApiIssue.NotFound
            code == "memo_not_found" -> ApiIssue.NotFound
            code == "conversation_changed" || code == "memo_changed" -> ApiIssue.Conflict
            code == "memo_request_conflict" -> ApiIssue.Conflict
            code in setOf("sheets_timeout", "sheets_write_unconfirmed", "sheets_invalid_response") -> ApiIssue.SaveUnconfirmed
            response.code() == 400 -> ApiIssue.InvalidInput
            else -> ApiIssue.Unavailable
        }
        throw ApiException(issue, mutation && issue == ApiIssue.SaveUnconfirmed)
    }
    response.body() ?: throw ApiException(ApiIssue.InvalidResponse, mutation)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: SessionChangedException) {
    throw ApiException(ApiIssue.SessionExpired)
} catch (_: InterruptedIOException) {
    currentCoroutineContext().ensureActive()
    throw ApiException(ApiIssue.Timeout, mutation)
} catch (_: JsonParseException) {
    throw ApiException(ApiIssue.InvalidResponse, mutation)
} catch (error: IOException) {
    currentCoroutineContext().ensureActive()
    throw ApiException(
        if (error is MalformedJsonException || error is EOFException) ApiIssue.InvalidResponse
        else ApiIssue.Network,
        mutation
    )
}
