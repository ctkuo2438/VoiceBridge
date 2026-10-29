package com.zhenting.voicebridge.ui.connection

import androidx.lifecycle.ViewModelStore
import com.zhenting.voicebridge.MainDispatcherRule
import com.zhenting.voicebridge.data.repository.HealthCheckResult
import com.zhenting.voicebridge.data.repository.HealthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class ControlledRepository : HealthRepository {
        var calls = 0
        var cancelled = false
        var response = CompletableDeferred<HealthCheckResult>()

        override suspend fun checkConnection(): HealthCheckResult {
            calls++
            return try {
                response.await()
            } catch (error: CancellationException) {
                cancelled = true
                throw error
            }
        }
    }

    @Test
    fun repeatedTapsShareOneCheckAndThenShowTheResult() = runTest {
        val repository = ControlledRepository()
        val viewModel = ConnectionViewModel(repository)

        viewModel.checkConnection()
        viewModel.checkConnection()
        assertTrue(viewModel.uiState.value.isChecking)
        runCurrent()
        assertEquals(1, repository.calls)

        repository.response.complete(HealthCheckResult.Healthy)
        runCurrent()
        assertFalse(viewModel.uiState.value.isChecking)
        assertSame(HealthCheckResult.Healthy, viewModel.uiState.value.result)
    }

    @Test
    fun aFailedCheckCanBeRetriedSuccessfully() = runTest {
        val repository = ControlledRepository()
        val viewModel = ConnectionViewModel(repository)
        viewModel.checkConnection()
        runCurrent()
        repository.response.complete(HealthCheckResult.Unreachable)
        runCurrent()
        assertSame(HealthCheckResult.Unreachable, viewModel.uiState.value.result)

        repository.response = CompletableDeferred()
        viewModel.checkConnection()
        assertTrue(viewModel.uiState.value.isChecking)
        assertNull(viewModel.uiState.value.result)
        runCurrent()
        repository.response.complete(HealthCheckResult.Healthy)
        runCurrent()
        assertEquals(2, repository.calls)
        assertSame(HealthCheckResult.Healthy, viewModel.uiState.value.result)
    }

    @Test
    fun clearingTheViewModelCancelsTheCheckWithoutShowingAFailure() = runTest {
        val repository = ControlledRepository()
        val viewModel = ConnectionViewModel(repository)
        val store = ViewModelStore()
        store.put("connection", viewModel)
        viewModel.checkConnection()
        runCurrent()

        store.clear()
        runCurrent()

        assertTrue(repository.cancelled)
        assertFalse(viewModel.uiState.value.isChecking)
        assertNull(viewModel.uiState.value.result)
    }
}
