package moe.rukamori.archivetune.sources.tidal

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.sources.createSourceHttpClient
import moe.rukamori.archivetune.sources.executeSourceRequest
import moe.rukamori.archivetune.sources.runSourceOperation
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

internal class TidalOAuthClient(val id: String, val secret: String) {
    override fun toString(): String = "TidalOAuthClient(redacted)"
}

internal class TidalDeviceAuthorization(
    val verificationUrl: String,
    val deviceCode: String,
    val expiresAtMs: Long,
    val intervalMs: Long,
) {
    override fun toString(): String = "TidalDeviceAuthorization(redacted)"
}

internal class TidalOAuth(
    private val http: OkHttpClient = createSourceHttpClient(),
) {
    suspend fun authorize(client: TidalOAuthClient): TidalDeviceAuthorization = withContext(Dispatchers.IO) {
        val (status, response) = runSourceOperation {
            request(
                "device_authorization",
                FormBody.Builder().add("client_id", client.id).add("scope", SCOPE).build(),
            )
        }

        if (status !in 200..299) throw TidalException(TidalFailure.Account)

        val verificationUrl = response.optString("verificationUriComplete")
        val url = (if ("://" in verificationUrl) verificationUrl else "https://$verificationUrl").toHttpUrlOrNull()
        val code = response.optString("deviceCode")
        val expires = response.optLong("expiresIn")

        if (url == null || !url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() || (url.host != "tidal.com" && !url.host.endsWith(".tidal.com")) ||
            code.isBlank() || expires !in 1..3600
        ) {
            throw TidalException(TidalFailure.Account)
        }

        TidalDeviceAuthorization(
            verificationUrl = url.toString(),
            deviceCode = code,
            expiresAtMs = SystemClock.elapsedRealtime() + expires * 1000L,
            intervalMs = response.optLong("interval", 5L).coerceIn(1, 60) * 1000L,
        )
    }

    suspend fun awaitApproval(client: TidalOAuthClient, authorization: TidalDeviceAuthorization): TidalCredentials = withContext(Dispatchers.IO) {
        var interval = authorization.intervalMs

        while (SystemClock.elapsedRealtime() < authorization.expiresAtMs) {
            delay(interval)
            currentCoroutineContext().ensureActive()

            if (SystemClock.elapsedRealtime() >= authorization.expiresAtMs) break

            val (status, response) = runSourceOperation {
                request(
                    "token",
                    FormBody.Builder()
                        .add("client_id", client.id)
                        .add("scope", SCOPE)
                        .add("device_code", authorization.deviceCode)
                        .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                        .build(),
                    client,
                )
            }
            currentCoroutineContext().ensureActive()

            if (status in 200..299) return@withContext credentials(response, playbackClient)

            when {
                status == 429 || response.optString("error") == "slow_down" -> interval = (interval + 5000L).coerceAtMost(60_000L)
                status == 400 && (response.optString("error") == "authorization_pending" || response.optInt("sub_status") == 1002) -> Unit
                response.optString("error") == "expired_token" -> throw TidalException(TidalFailure.Expired)
                response.optString("error") == "access_denied" -> throw TidalException(TidalFailure.Denied)
                else -> throw TidalException(TidalFailure.Account)
            }
        }

        throw TidalException(TidalFailure.Expired)
    }

    fun refresh(credentials: TidalCredentials): TidalCredentials {
        val client = credentials.oauthClient ?: throw TidalException(TidalFailure.Account)
        val refreshToken = credentials.refreshToken ?: throw TidalException(TidalFailure.Account)
        val (status, response) = request(
            "token",
            FormBody.Builder()
                .add("client_id", client.id)
                .add("refresh_token", refreshToken)
                .add("grant_type", "refresh_token")
                .add("scope", SCOPE)
                .build(),
            client,
        )

        if (status !in 200..299) throw TidalException(TidalFailure.Account)

        return credentials(response, client, refreshToken)
    }

    private fun credentials(response: JSONObject, client: TidalOAuthClient, previousRefreshToken: String? = null): TidalCredentials {
        val accessToken = response.optString("access_token")
        val refreshToken = response.optString("refresh_token").ifBlank { previousRefreshToken.orEmpty() }
        val expires = response.optLong("expires_in")

        if (accessToken.length !in 20..8192 || refreshToken.length !in 20..8192 || expires !in 1..604800) {
            throw TidalException(TidalFailure.Account)
        }

        return TidalCredentials(accessToken, refreshToken, client, System.currentTimeMillis() + expires * 1000L)
    }

    private fun request(path: String, form: FormBody, client: TidalOAuthClient? = null): Pair<Int, JSONObject> {
        val request = Request.Builder()
            .url("https://auth.tidal.com/v1/oauth2/$path")
            .header("Accept", "application/json")
            .header("X-Platform", "android")
            .post(form)

        client?.let { request.header("Authorization", Credentials.basic(it.id, it.secret)) }

        return try {
            http.executeSourceRequest(request.build()) { response ->
                if (response.code >= 500 || response.code == 408) throw TidalException(TidalFailure.Network)

                val source = response.body.source()

                if (source.request(65_537L)) throw TidalException(TidalFailure.Network)

                response.code to JSONObject(source.readUtf8())
            }
        } catch (failure: TidalException) {
            throw failure
        } catch (_: Exception) {
            throw TidalException(TidalFailure.Network)
        }
    }

    companion object {
        val client = TidalOAuthClient("fX2JxdmntZWK0ixT", "1Nm5AfDAjxrgJFJbKNWLeAyKGVGmINuXPPLHVXAvxAg=")

        private val playbackClient = TidalOAuthClient("lw3vR6GE1vtNBsjv", "Y8tIpqKJxs9BEIwYr0I9bSbMWDsogXJx9LaN3mCHwD4%3D")

        private const val SCOPE = "r_usr+w_usr+w_sub"
    }
}
