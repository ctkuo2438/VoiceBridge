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
import com.zhenting.voicebridge.data.local.RecoveryStore
import com.zhenting.voicebridge.data.local.MemoDraft
import java.io.IOException
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

    @Test
    fun recoveryIsScopedToTheAccountAndReturnsOnlyWhenThatAccountSignsIn() = runTest {
        val repo = FakeAuth()
        val recoveries = mutableMapOf<String, RecoveryStore>()
        val parent = ViewModelStore()
        val model = ViewModelProvider(parent, WorkspaceViewModel.factory(repo) { id ->
            recoveries.getOrPut(id) { RecoveryStore() }
        })[WorkspaceViewModel::class.java]
        try {
            repo.user.value = UserProfile("a", "Alice", null, null); runCurrent()
            model.workspace.value!!.recovery.update { it.copy(memo = MemoDraft("Alice unfinished note")) }
            repo.user.value = UserProfile("b", "Bob", null, null); runCurrent()
            assertEquals("", model.workspace.value!!.recovery.snapshot.memo.text)
            repo.user.value = UserProfile("a", "Alice", null, null); runCurrent()
            assertEquals("Alice unfinished note", model.workspace.value!!.recovery.snapshot.memo.text)
        } finally { parent.clear() }
    }

    @Test
    fun localReadFailureIsReportedAndCanBeRetriedWithoutCreatingAnEmptyWorkspace() = runTest {
        val repo = FakeAuth()
        var readable = false
        val recovery = RecoveryStore().apply { update { it.copy(memo = MemoDraft("Keep this draft")) } }
        val parent = ViewModelStore()
        val model = ViewModelProvider(parent, WorkspaceViewModel.factory(repo) {
            if (!readable) throw IOException("Storage unavailable")
            recovery
        })[WorkspaceViewModel::class.java]
        try {
            repo.user.value = UserProfile("a", "Alice", null, null); runCurrent()
            assertTrue(model.storageError.value)
            assertNull(model.workspace.value)
            readable = true
            model.retryRestore(); runCurrent()
            assertFalse(model.storageError.value)
            assertEquals("Keep this draft", model.workspace.value!!.recovery.snapshot.memo.text)
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
