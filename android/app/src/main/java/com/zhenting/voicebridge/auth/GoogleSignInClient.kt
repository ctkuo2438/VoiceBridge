package com.zhenting.voicebridge.auth

import android.app.Activity
import android.content.Context
import android.content.MutableContextWrapper
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.zhenting.voicebridge.data.repository.AuthException
import com.zhenting.voicebridge.data.repository.AuthIssue
import com.zhenting.voicebridge.data.repository.GoogleLoginChallenge

class GoogleSignInClient(context: Context) {
    private val credentialManager = CredentialManager.create(context.applicationContext)

    // 3. Sign in + nonce to Google and get 4. Google ID token
    suspend fun getIdToken(activity: Activity, challenge: GoogleLoginChallenge): String {
        // Use the Web client ID and the exact nonce issued by Flask.
        val option = GetSignInWithGoogleOption.Builder(challenge.clientId)
            .setNonce(challenge.nonce)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        try {
            val result = credentialManager.getCredential(MutableContextWrapper(activity), request)
            val credential = result.credential
            if (credential !is CustomCredential ||
                credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) throw AuthException(AuthIssue.GoogleUnavailable)
            return GoogleIdTokenCredential.createFrom(credential.data).idToken
        } catch (_: GetCredentialCancellationException) {
            throw AuthException(AuthIssue.Cancelled)
        } catch (_: NoCredentialException) {
            throw AuthException(AuthIssue.GoogleUnavailable)
        } catch (_: GetCredentialException) {
            throw AuthException(AuthIssue.GoogleUnavailable)
        } catch (_: GoogleIdTokenParsingException) {
            throw AuthException(AuthIssue.GoogleUnavailable)
        }
    }

    suspend fun clearSession() {
        credentialManager.clearCredentialState(ClearCredentialStateRequest())
    }
}
