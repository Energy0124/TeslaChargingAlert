package com.teslacharging.alert

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

object TeslaAuthManager {
    private const val AUTHORIZE_URL = "https://auth.tesla.com/oauth2/v3/authorize"
    private const val TOKEN_URL = "https://fleet-auth.prd.vn.cloud.tesla.com/oauth2/v3/token"
    private const val REDIRECT_SCHEME = "teslachargingalert"
    private const val REDIRECT_HOST = "auth"
    private const val REDIRECT_PATH = "/callback"
    private const val TOKEN_REFRESH_SKEW_SECONDS = 300L
    private const val CONNECT_TIMEOUT = 30_000
    private const val READ_TIMEOUT = 30_000
    private const val SCOPES = "openid offline_access vehicle_device_data vehicle_cmds"

    val redirectUri: String = "$REDIRECT_SCHEME://$REDIRECT_HOST$REDIRECT_PATH"

    fun buildLoginIntent(context: Context, clientId: String, baseUrl: String): Intent {
        if (clientId.isBlank()) throw AuthException("Enter your Tesla client ID first.")
        if (Prefs.getClientSecret(context).isBlank()) {
            throw AuthException("Enter your Tesla client secret first.")
        }

        val state = randomState()
        Prefs.setPendingAuthState(context, state)
        Prefs.setPendingAuthBaseUrl(context, baseUrl)

        val uri = Uri.parse(AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("locale", java.util.Locale.getDefault().toLanguageTag())
            .appendQueryParameter("prompt", "login")
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("state", state)
            .build()

        return Intent(Intent.ACTION_VIEW, uri)
    }

    fun completeLogin(context: Context, callbackUri: Uri) {
        val error = callbackUri.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            val description = callbackUri.getQueryParameter("error_description")
            Prefs.clearPendingAuth(context)
            throw AuthException(description ?: "Tesla sign-in failed: $error")
        }

        val returnedState = callbackUri.getQueryParameter("state").orEmpty()
        val expectedState = Prefs.getPendingAuthState(context)
        if (expectedState.isBlank() || returnedState != expectedState) {
            Prefs.clearPendingAuth(context)
            throw AuthException("Tesla sign-in state did not match. Please try again.")
        }

        val code = callbackUri.getQueryParameter("code").orEmpty()
        if (code.isBlank()) {
            Prefs.clearPendingAuth(context)
            throw AuthException("Tesla sign-in did not return an authorization code.")
        }

        val clientId = Prefs.getClientId(context)
        val clientSecret = Prefs.getClientSecret(context)
        val audience = Prefs.getPendingAuthBaseUrl(context)
        if (clientId.isBlank() || clientSecret.isBlank()) {
            Prefs.clearPendingAuth(context)
            throw AuthException("Missing Tesla client credentials. Save them and try again.")
        }

        val response = postForm(
            TOKEN_URL,
            mapOf(
                "grant_type" to "authorization_code",
                "client_id" to clientId,
                "client_secret" to clientSecret,
                "code" to code,
                "audience" to audience,
                "redirect_uri" to redirectUri
            )
        )
        saveTokenResponse(context, response, allowMissingRefreshToken = false)
        Prefs.setApiBaseUrl(context, audience)
        Prefs.clearPendingAuth(context)
    }

    fun getValidAccessToken(context: Context, forceRefresh: Boolean = false): String {
        val currentToken = Prefs.getApiToken(context)
        val expiresAt = Prefs.getAccessTokenExpiresAt(context)
        val now = System.currentTimeMillis() / 1000

        if (!forceRefresh &&
            currentToken.isNotBlank() &&
            expiresAt > now + TOKEN_REFRESH_SKEW_SECONDS
        ) {
            return currentToken
        }

        return refreshAccessToken(context)
    }

    fun clearSession(context: Context) {
        Prefs.clearAuthSession(context)
    }

    private fun refreshAccessToken(context: Context): String {
        val refreshToken = Prefs.getRefreshToken(context)
        val clientId = Prefs.getClientId(context)
        if (refreshToken.isBlank() || clientId.isBlank()) {
            throw AuthException("Tesla login has expired. Please sign in again.")
        }

        val response = postForm(
            TOKEN_URL,
            mapOf(
                "grant_type" to "refresh_token",
                "client_id" to clientId,
                "refresh_token" to refreshToken
            )
        )

        return try {
            saveTokenResponse(context, response, allowMissingRefreshToken = true)
        } catch (e: AuthException) {
            Prefs.clearAuthSession(context)
            throw e
        }
    }

    private fun saveTokenResponse(
        context: Context,
        jsonBody: String,
        allowMissingRefreshToken: Boolean
    ): String {
        val json = JSONObject(jsonBody)
        val accessToken = json.optString("access_token", "")
        if (accessToken.isBlank()) {
            throw AuthException("Tesla did not return an access token.")
        }

        val expiresIn = json.optLong("expires_in", 0L)
        val refreshToken = json.optString("refresh_token", "")
        if (!allowMissingRefreshToken && refreshToken.isBlank()) {
            throw AuthException("Tesla did not return a refresh token. Make sure offline access is enabled.")
        }

        Prefs.setApiToken(context, accessToken)
        if (refreshToken.isNotBlank()) {
            Prefs.setRefreshToken(context, refreshToken)
        }
        Prefs.setAccessTokenExpiresAt(
            context,
            (System.currentTimeMillis() / 1000) + expiresIn
        )
        return accessToken
    }

    private fun postForm(urlString: String, params: Map<String, String>): String {
        val payload = params.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }

        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
        }

        return try {
            conn.outputStream.use { it.write(payload.toByteArray(StandardCharsets.UTF_8)) }
            when (conn.responseCode) {
                200 -> conn.inputStream.bufferedReader().readText()
                400, 401 -> {
                    val body = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                    throw AuthException(parseError(body))
                }
                else -> {
                    val body = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                    throw IOException("HTTP ${conn.responseCode}: $body")
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun parseError(body: String): String {
        return runCatching {
            val json = JSONObject(body)
            json.optString("error_description")
                .ifBlank { json.optString("error") }
                .ifBlank { "Tesla authentication failed." }
        }.getOrElse {
            if (body.isBlank()) "Tesla authentication failed." else body
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.toString())

    private fun randomState(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    class AuthException(message: String) : Exception(message)
}
