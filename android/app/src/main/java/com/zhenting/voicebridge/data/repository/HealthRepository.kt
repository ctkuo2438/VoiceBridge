package com.zhenting.voicebridge.data.repository

import com.google.gson.JsonParseException
import com.google.gson.stream.MalformedJsonException
import com.zhenting.voicebridge.data.remote.VoiceBridgeApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.EOFException
import java.io.IOException

sealed interface HealthCheckResult {
    data object Healthy : HealthCheckResult
    data object Unreachable : HealthCheckResult
    data object InvalidResponse : HealthCheckResult
    data class HttpError(val statusCode: Int) : HealthCheckResult
}

interface HealthRepository {
    suspend fun checkConnection(): HealthCheckResult
}

class RemoteHealthRepository(private val api: VoiceBridgeApi) : HealthRepository {
    override suspend fun checkConnection(): HealthCheckResult = try {
        val response = api.getHealth()
        when {
            !response.isSuccessful -> HealthCheckResult.HttpError(response.code())
            response.body()?.status == "ok" -> HealthCheckResult.Healthy
            else -> HealthCheckResult.InvalidResponse
        }
    } catch (_: JsonParseException) {
        HealthCheckResult.InvalidResponse
    } catch (error: IOException) {
        currentCoroutineContext().ensureActive()
        when (error) {
            is MalformedJsonException, is EOFException -> HealthCheckResult.InvalidResponse
            else -> HealthCheckResult.Unreachable
        }
    }
}
