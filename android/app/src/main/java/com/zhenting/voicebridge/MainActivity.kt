package com.zhenting.voicebridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhenting.voicebridge.auth.GoogleSignInClient
import com.zhenting.voicebridge.ui.auth.AuthRoute
import com.zhenting.voicebridge.ui.auth.AuthViewModel
import com.zhenting.voicebridge.ui.connection.ConnectionRoute
import com.zhenting.voicebridge.ui.connection.ConnectionViewModel
import com.zhenting.voicebridge.ui.theme.VoiceBridgeTheme
import com.zhenting.voicebridge.ui.home.HomeRoute
import com.zhenting.voicebridge.ui.home.WorkspaceViewModel
import com.zhenting.voicebridge.data.audio.AndroidAudioRecorder
import com.zhenting.voicebridge.data.audio.AndroidAudioPlayer

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as VoiceBridgeApplication).container
        val googleSignInClient = GoogleSignInClient(applicationContext)
        val audioContext = applicationContext
        setContent {
            VoiceBridgeTheme {
                val authViewModel: AuthViewModel = viewModel(
                    factory = AuthViewModel.factory(container.authRepository)
                )
                val workspaceViewModel: WorkspaceViewModel = viewModel(
                    factory = WorkspaceViewModel.factory(container.authRepository)
                )
                val connectionViewModel: ConnectionViewModel = viewModel(
                    factory = ConnectionViewModel.factory(container.healthRepository)
                )
                var showConnectionTest by rememberSaveable { mutableStateOf(false) }
                BackHandler(enabled = showConnectionTest) { showConnectionTest = false }
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    if (showConnectionTest && BuildConfig.DEBUG) {
                        Column(Modifier.fillMaxSize().padding(innerPadding)) {
                            TextButton(onClick = { showConnectionTest = false }) {
                                Text(stringResource(R.string.auth_back))
                            }
                            ConnectionRoute(
                                viewModel = connectionViewModel,
                                baseUrl = container.baseUrl,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    } else {
                        AuthRoute(
                            viewModel = authViewModel,
                            getGoogleIdToken = { challenge ->
                                googleSignInClient.getIdToken(this@MainActivity, challenge)
                            },
                            clearGoogleSession = googleSignInClient::clearSession,
                            modifier = Modifier.padding(innerPadding),
                            onConnectionTest = if (BuildConfig.DEBUG) {
                                { showConnectionTest = true }
                            } else null,
                            signedInContent = { state, onSignOut, contentModifier ->
                                HomeRoute(
                                    workspaceViewModel = workspaceViewModel,
                                    authState = state,
                                    createChatRepository = container::chatRepository,
                                    createMemoRepository = container::memoRepository,
                                    onSignOut = onSignOut,
                                    onDismissAuthError = authViewModel::dismissError,
                                    modifier = contentModifier,
                                    createSpeechRepository = container::speechRepository,
                                    createAudioRecorder = { AndroidAudioRecorder(audioContext) },
                                    createTtsRepository = container::ttsRepository,
                                    createAudioPlayer = { AndroidAudioPlayer(audioContext) }
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}
