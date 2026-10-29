package com.zhenting.voicebridge.data.remote

import com.zhenting.voicebridge.data.session.SessionStore
import com.zhenting.voicebridge.data.session.SessionCredentials
import com.zhenting.voicebridge.data.session.SessionChangedException
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

class SessionInterceptor(
    private val sessionStore: SessionStore,
    private val baseUrl: HttpUrl
) : Interceptor {
    private val publicRequests = setOf(
        "GET" to baseUrl.resolve("health")!!.encodedPath,
        "POST" to baseUrl.resolve("auth/google/challenge")!!.encodedPath,
        "POST" to baseUrl.resolve("auth/google")!!.encodedPath
    )

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val isBackend = url.scheme == baseUrl.scheme && url.host == baseUrl.host &&
            url.port == baseUrl.port && url.encodedPath.startsWith(baseUrl.encodedPath)
        if (!isBackend || (request.method to url.encodedPath) in publicRequests) {
            return chain.proceed(request)
        }

        val credentials = request.tag(SessionCredentials::class.java)
        if (credentials != null && !sessionStore.matches(credentials)) throw SessionChangedException()
        val token = credentials?.token ?: sessionStore.accessToken()
        val authenticated = request.newBuilder().apply {
            // 8. Bearer token
            // The current Flask session is the only source of Bearer credentials.
            removeHeader("Authorization")
            if (token != null) header("Authorization", "Bearer $token")
        }.build()
        val response = chain.proceed(authenticated)
        if (response.code == 401 && token != null) sessionStore.clearIfMatches(token)
        return response
    }
}
