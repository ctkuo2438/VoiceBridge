package com.zhenting.voicebridge.data.remote

import com.zhenting.voicebridge.data.session.SessionStore
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {
    fun create(
        baseUrl: String,
        httpClient: OkHttpClient = defaultHttpClient(),
        sessionStore: SessionStore? = null,
        requestTimeoutSeconds: Long? = null
    ): VoiceBridgeApi {
        val client = httpClient.newBuilder().apply {
            if (requestTimeoutSeconds != null) {
                readTimeout(requestTimeoutSeconds, TimeUnit.SECONDS)
                callTimeout(requestTimeoutSeconds + 15, TimeUnit.SECONDS)
            }
            if (sessionStore != null) {
                addInterceptor(SessionInterceptor(sessionStore, baseUrl.toHttpUrl()))
            }
        }.build()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(VoiceBridgeApi::class.java)
    }

    private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
        // Allow time for Flask to verify the Google ID token with Google.
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(false)
        .retryOnConnectionFailure(false)
        .build()
}
