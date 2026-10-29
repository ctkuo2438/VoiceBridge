package com.zhenting.voicebridge.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhenting.voicebridge.MainDispatcherRule
import com.zhenting.voicebridge.data.model.UserProfile
import com.zhenting.voicebridge.data.repository.AuthRepository
import com.zhenting.voicebridge.data.repository.GoogleLoginChallenge
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()

    @Test
    fun sameUserKeepsTheWorkspaceAndLogoutClearsAllChildViewModels() = runTest {
        val repo = FakeAuth()
        val parent = ViewModelStore()
        val model = ViewModelProvider(parent, WorkspaceViewModel.factory(repo))[WorkspaceViewModel::class.java]
        try {
            repo.user.value = UserProfile("a", "Alice", null, null); runCurrent()
            val first = model.workspace.value!!
            val child = ViewModelProvider(first, viewModelFactory { initializer { Child() } })[Child::class.java]
            repo.user.value = repo.user.value!!.copy(name = "Updated Alice"); runCurrent()
            assertSame(first, model.workspace.value)
            assertFalse(child.cleared)
            repo.user.value = null; runCurrent()
            assertTrue(child.cleared)
            assertNull(model.workspace.value)
            repo.user.value = UserProfile("b", "Bob", null, null); runCurrent()
            assertNotSame(first, model.workspace.value)
            assertEquals("b", model.workspace.value!!.userId)
        } finally { parent.clear() }
    }

    private class Child : ViewModel() {
        var cleared = false
        override fun onCleared() { cleared = true }
    }
    private class FakeAuth : AuthRepository {
        override val user = MutableStateFlow<UserProfile?>(null)
        override suspend fun createGoogleChallenge(): GoogleLoginChallenge = error("Unused")
        override suspend fun loginWithGoogle(challenge: GoogleLoginChallenge, idToken: String) = Unit
        override suspend fun refreshUser() = Unit
        override suspend fun logout() { user.value = null }
    }
}
