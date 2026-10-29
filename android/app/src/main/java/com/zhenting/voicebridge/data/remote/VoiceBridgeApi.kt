package com.zhenting.voicebridge.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Tag
import retrofit2.http.Multipart
import retrofit2.http.Part
import okhttp3.MultipartBody
import com.zhenting.voicebridge.data.session.SessionCredentials

interface VoiceBridgeApi {
    @GET("health")
    suspend fun getHealth(): Response<HealthResponse>

    @POST("auth/google/challenge")
    suspend fun createGoogleChallenge(): Response<GoogleChallengeResponse>

    @POST("auth/google")
    suspend fun loginWithGoogle(
        @Body request: GoogleLoginRequest
    ): Response<GoogleLoginResponse>

    @GET("auth/me")
    suspend fun getCurrentUser(): Response<CurrentUserResponse>

    @POST("auth/logout")
    suspend fun logout(): Response<LogoutResponse>

    @POST("chat")
    suspend fun sendMessage(
        @Body request: ChatRequest,
        @Tag credentials: SessionCredentials
    ): Response<ChatResponse>

    @GET("conversations")
    suspend fun getConversations(@Tag credentials: SessionCredentials): Response<ConversationsResponse>

    @GET("conversations/{conversationId}/messages")
    suspend fun getMessages(
        @Path("conversationId") conversationId: String,
        @Tag credentials: SessionCredentials
    ): Response<ConversationMessagesResponse>

    @POST("memos")
    suspend fun saveMemo(
        @Body request: MemoRequest,
        @Tag credentials: SessionCredentials
    ): Response<MemoSaveResponse>

    @GET("memos")
    suspend fun getMemos(@Tag credentials: SessionCredentials): Response<MemosResponse>

    @Multipart
    @POST("transcribe")
    suspend fun transcribe(
        @Part file: MultipartBody.Part,
        @Tag credentials: SessionCredentials
    ): Response<TranscriptionResponse>
}
