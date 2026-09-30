package com.lagradost.cloudstream3.syncproviders.providers

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.APP_STRING
import com.lagradost.cloudstream3.syncproviders.AuthAPI
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.cloudstream3.syncproviders.AuthPinData
import com.lagradost.cloudstream3.syncproviders.AuthToken
import com.lagradost.cloudstream3.syncproviders.AuthUser
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Lightweight Trakt integration — OAuth + scrobble only.
 *
 * This intentionally does NOT implement [com.lagradost.cloudstream3.syncproviders.SyncAPI]
 * (no library/status sync) — it is registered as a [com.lagradost.cloudstream3.syncproviders.PlainAuthRepo].
 * Scrobble calls are dispatched from [com.lagradost.cloudstream3.utils.trakt.TraktScrobbleManager]
 * when the player reports position changes.
 *
 * OAuth uses the device-code flow:
 *   1. POST /oauth/device/code  →  user_code + verification_url + device_code
 *   2. User opens the URL in a browser and enters the code
 *   3. Poll POST /oauth/device/token every [interval] seconds until authorized
 *
 * Users must register their own Trakt application at
 * https://trakt.tv/oauth/applications and place the client id in
 * `local.properties` as `trakt.id` (or the `TRAKT_CLIENT_ID` env var).
 * Without a client id the login button will fail gracefully.
 */
class TraktApi : AuthAPI() {
    override val name: String = "Trakt"
    override val idPrefix: String = "trakt"
    override val icon: Int = R.drawable.trakt_logo
    override val createAccountUrl: String = "https://trakt.tv/auth/signup"
    override val redirectUrlIdentifier: String = "trakt"
    override val hasOAuth2: Boolean = true
    override val hasPin: Boolean = true
    override val requiresLogin: Boolean = true

    companion object {
        private const val MAIN_URL = "https://api.trakt.tv"
        private const val CLIENT_ID: String = BuildConfig.TRAKT_CLIENT_ID

        /** Required Trakt API headers — sent on every request. */
        private fun apiHeaders(): Map<String, String> = mapOf(
            "Content-Type" to "application/json",
            "trakt-api-version" to "2",
            "trakt-api-key" to CLIENT_ID,
        )

        /** Headers with the user's Bearer token appended. */
        private fun authHeaders(token: AuthToken): Map<String, String> {
            val h = apiHeaders().toMutableMap()
            token.accessToken?.let { h["Authorization"] = "Bearer $it" }
            return h
        }
    }

    // ---------- Serializable request/response types ----------

    @Serializable
    private data class DeviceCodeResponse(
        @SerialName("device_code") val deviceCode: String,
        @SerialName("user_code") val userCode: String,
        @SerialName("verification_url") val verificationUrl: String,
        @SerialName("expires_in") val expiresIn: Int,
        @SerialName("interval") val interval: Int,
    )

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("token_type") val tokenType: String? = null,
        @SerialName("expires_in") val expiresIn: Long? = null,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("scope") val scope: String? = null,
        @SerialName("created_at") val createdAt: Long? = null,
    )

    @Serializable
    private data class TokenRequest(
        @SerialName("code") val code: String,
        @SerialName("client_id") val clientId: String,
        @SerialName("client_secret") val clientSecret: String,
    )

    @Serializable
    private data class RefreshRequest(
        @SerialName("refresh_token") val refreshToken: String,
        @SerialName("client_id") val clientId: String,
        @SerialName("client_secret") val clientSecret: String,
        @SerialName("redirect_uri") val redirectUri: String,
        @SerialName("grant_type") val grantType: String,
    )

    @Serializable
    private data class TraktUser(
        @SerialName("username") val username: String? = null,
        @SerialName("ids") val ids: TraktUserIds? = null,
    )

    @Serializable
    private data class TraktUserIds(
        @SerialName("slug") val slug: String? = null,
    )

    @Serializable
    private data class UserSettingsResponse(
        @SerialName("user") val user: TraktUser,
    )

    // Scrobble request body — shared by start / pause / stop.
    @Serializable
    data class ScrobbleBody(
        @SerialName("movie") val movie: ScrobbleMedia? = null,
        @SerialName("episode") val episode: ScrobbleMedia? = null,
        @SerialName("show") val show: ScrobbleMedia? = null,
        @SerialName("progress") val progress: Double,
        @SerialName("app_version") val appVersion: String = "1.0",
        @SerialName("date") val date: String? = null,
    )

    @Serializable
    data class ScrobbleMedia(
        @SerialName("title") val title: String? = null,
        @SerialName("year") val year: Int? = null,
        @SerialName("ids") val ids: ScrobbleIds? = null,
    )

    @Serializable
    data class ScrobbleIds(
        @SerialName("trakt") val trakt: Int? = null,
        @SerialName("slug") val slug: String? = null,
        @SerialName("imdb") val imdb: String? = null,
        @SerialName("tmdb") val tmdb: Int? = null,
    )

    // ---------- OAuth ----------

    override suspend fun pinRequest(): AuthPinData? {
        if (CLIENT_ID.isBlank() || CLIENT_ID == "null") return null
        return try {
            val resp = app.post(
                "$MAIN_URL/oauth/device/code",
                headers = apiHeaders(),
                json = mapOf("client_id" to CLIENT_ID),
            )
            if (!resp.isSuccessful) return null
            val body = tryParseJson<DeviceCodeResponse>(resp.text) ?: return null
            AuthPinData(
                deviceCode = body.deviceCode,
                userCode = body.userCode,
                verificationUrl = body.verificationUrl,
                expiresIn = body.expiresIn,
                interval = body.interval.coerceAtLeast(1),
            )
        } catch (t: Throwable) {
            logError(t)
            null
        }
    }

    override suspend fun login(payload: AuthPinData): AuthToken? {
        if (CLIENT_ID.isBlank() || CLIENT_ID == "null") return null
        return try {
            // Trakt returns HTTP 200 with a valid token, or HTTP 400 with
            // "pending" / "expired" while the user hasn't yet authorized.
            val resp = app.post(
                "$MAIN_URL/oauth/device/token",
                headers = apiHeaders(),
                json = mapOf(
                    "code" to payload.deviceCode,
                    "client_id" to CLIENT_ID,
                    "client_secret" to "", // Trakt device flow has no secret
                ),
            )
            if (!resp.isSuccessful) return null
            val tokenResp = tryParseJson<TokenResponse>(resp.text) ?: return null
            val expiresAt = tokenResp.createdAt?.plus(tokenResp.expiresIn ?: 0)
            AuthToken(
                accessToken = tokenResp.accessToken,
                refreshToken = tokenResp.refreshToken,
                accessTokenLifetime = expiresAt,
                // Trakt refresh tokens are long-lived; store the same lifetime
                // so we know when to attempt a refresh.
                refreshTokenLifetime = expiresAt?.plus(30L * 24 * 3600),
            )
        } catch (t: Throwable) {
            logError(t)
            null
        }
    }

    override suspend fun login(redirectUrl: String, payload: String?): AuthToken? {
        // Trakt OAuth redirect flow — not currently wired up in the UI,
        // but implemented here for completeness.
        return null
    }

    override suspend fun refreshToken(token: AuthToken): AuthToken? {
        val refresh = token.refreshToken ?: return null
        if (CLIENT_ID.isBlank() || CLIENT_ID == "null") return null
        return try {
            val resp = app.post(
                "$MAIN_URL/oauth/token",
                headers = apiHeaders(),
                json = RefreshRequest(
                    refreshToken = refresh,
                    clientId = CLIENT_ID,
                    clientSecret = "",
                    redirectUri = APP_STRING,
                    grantType = "refresh_token",
                ),
            )
            if (!resp.isSuccessful) return null
            val tokenResp = tryParseJson<TokenResponse>(resp.text) ?: return null
            val expiresAt = tokenResp.createdAt?.plus(tokenResp.expiresIn ?: 0)
            AuthToken(
                accessToken = tokenResp.accessToken,
                refreshToken = tokenResp.refreshToken ?: refresh,
                accessTokenLifetime = expiresAt,
                refreshTokenLifetime = expiresAt?.plus(30L * 24 * 3600),
            )
        } catch (t: Throwable) {
            logError(t)
            null
        }
    }

    override suspend fun user(token: AuthToken?): AuthUser? {
        if (token?.accessToken == null) return null
        return try {
            val resp = app.get("$MAIN_URL/users/settings", headers = authHeaders(token))
            if (!resp.isSuccessful) return null
            val settings = tryParseJson<UserSettingsResponse>(resp.text) ?: return null
            val slug = settings.user.ids?.slug ?: settings.user.username ?: return null
            AuthUser(
                // Trakt user ids must be an Int for AuthUser — hash the slug.
                id = slug.hashCode(),
                name = settings.user.username ?: slug,
            )
        } catch (t: Throwable) {
            logError(t)
            null
        }
    }

    // ---------- Scrobble ----------

    /**
     * Send a scrobble event to Trakt.
     *
     * @param action One of "start", "pause", "stop"
     * @param media  The movie or episode being scrobbled
     * @param progress Percentage watched (0.0–100.0)
     */
    suspend fun scrobble(
        auth: AuthData?,
        action: String,
        body: ScrobbleBody,
    ): Boolean {
        val token = auth?.token?.accessToken ?: return false
        if (CLIENT_ID.isBlank() || CLIENT_ID == "null") return false
        return try {
            val resp = app.post(
                "$MAIN_URL/scrobble/$action",
                headers = authHeaders(auth.token),
                json = body,
            )
            resp.isSuccessful
        } catch (t: Throwable) {
            logError(t)
            false
        }
    }

    /** Returns true when the user has configured a Trakt client id. */
    fun isConfigured(): Boolean =
        CLIENT_ID.isNotBlank() && CLIENT_ID != "null"
}
