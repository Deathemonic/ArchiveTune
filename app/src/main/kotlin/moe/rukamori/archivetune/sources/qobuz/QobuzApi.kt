package moe.rukamori.archivetune.sources.qobuz

import java.io.IOException
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.suspendCancellableCoroutine
import moe.rukamori.archivetune.sources.createSourceHttpClient
import moe.rukamori.archivetune.sources.executeSourceRequest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

internal class QobuzApi(
    private val client: OkHttpClient = createSourceHttpClient(),
) {
    suspend fun validate(credentials: QobuzCredentials) {
        val user = requestCancellable("user/get", credentials)

        if (user.optLong("id") <=
            0L
        ) {
            throw QobuzApiException(401, QobuzFailureReason.Token, QobuzRequestStage.Account)
        }

        try {
            requestCancellable(
                "track/getFileUrl",
                credentials,
                streamParameters(credentials, SIGNATURE_TEST_TRACK_ID, 6),
            )
        } catch (failure: QobuzApiException) {
            if (failure.reason in
                setOf(
                    QobuzFailureReason.Signature,
                    QobuzFailureReason.AppId,
                    QobuzFailureReason.Token,
                    QobuzFailureReason.Clock,
                )
            ) {
                throw failure
            }

            if (failure.statusCode == 400) {
                throw QobuzApiException(
                    400,
                    QobuzFailureReason.Signature,
                    QobuzRequestStage.Stream,
                )
            }

            if (failure.statusCode !in setOf(401, 403, 404)) throw failure
        }
    }

    fun search(credentials: QobuzCredentials, query: QobuzSongQuery): List<QobuzTrack> = search(credentials, "${query.artists.firstOrNull().orEmpty()} ${query.title}")

    fun search(credentials: QobuzCredentials, query: String): List<QobuzTrack> {
        val response = request(
            "track/search",
            credentials,
            mapOf("query" to query, "limit" to "50"),
        )

        return parseTracks(response)
    }

    suspend fun searchTracks(credentials: QobuzCredentials, query: String): List<QobuzTrack> = parseTracks(
        requestCancellable(
            "track/search",
            credentials,
            mapOf(
                "query" to query,
                "limit" to "50",
            ),
        ),
    )

    private fun parseTracks(response: JSONObject): List<QobuzTrack> {
        val items = response.optJSONObject("tracks")?.optJSONArray("items") ?: return emptyList()

        return (0 until minOf(items.length(), 50)).mapNotNull { index ->
            val track = items.optJSONObject(index) ?: return@mapNotNull null
            val album = track.optJSONObject("album")
            val id = track.optLong("id")
            val title = (track.opt("title") as? String)?.takeIf(String::isNotBlank)

            if (id <= 0L || title == null || track.optInt("duration") < 0) return@mapNotNull null
            QobuzTrack(
                id = id,
                title = title,
                version = (track.opt("version") as? String)?.takeUnless { it.isBlank() || it == "null" }.orEmpty(),
                artist = (track.optJSONObject("performer")?.opt("name") as? String)?.takeIf(String::isNotBlank)
                    ?: (album?.optJSONObject("artist")?.opt("name") as? String)?.takeIf(String::isNotBlank).orEmpty(),
                album = (album?.opt("title") as? String)?.takeIf(String::isNotBlank).orEmpty(),
                duration = track.optInt("duration"),
                streamable = track.optBoolean("streamable", false),
                explicit = track.optBoolean("parental_warning", false),
                thumbnailUrl = (album?.optJSONObject("image")?.opt("small") as? String)?.takeIf(String::isNotBlank)?.takeIf {
                    it.startsWith("https://")
                },
            )
        }
    }

    fun stream(
        credentials: QobuzCredentials,
        track: QobuzTrack,
        formats: List<Int> = listOf(27, 7, 6),
    ): QobuzStream {
        for (formatId in formats) {
            val response = try {
                signedStreamRequest(credentials, track.id, formatId)
            } catch (failure: QobuzApiException) {
                if (failure.reason in
                    setOf(
                        QobuzFailureReason.AppId,
                        QobuzFailureReason.Token,
                        QobuzFailureReason.Signature,
                        QobuzFailureReason.Clock,
                        QobuzFailureReason.RateLimit,
                    )
                ) {
                    throw failure
                }

                if (failure.statusCode in listOf(400, 403, 404) &&
                    formatId != formats.last()
                ) {
                    continue
                }
                throw failure
            }

            val receivedUrl = response.optString("url").toHttpUrlOrNull()
                ?: throw QobuzApiException(200, QobuzFailureReason.Format, QobuzRequestStage.Stream)
            val url = if (receivedUrl.scheme == "http") {
                receivedUrl.newBuilder().scheme("https").build()
            } else {
                receivedUrl
            }

            if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty()) {
                throw QobuzApiException(200, QobuzFailureReason.Transport, QobuzRequestStage.Stream)
            }

            if (response.optBoolean("sample", false)) {
                throw QobuzApiException(200, QobuzFailureReason.Preview, QobuzRequestStage.Stream)
            }

            if (response.optInt("format_id") !in formats ||
                response.optString(
                    "mime_type",
                ).substringBefore(';').trim().lowercase(Locale.ROOT) !in
                (
                    if (response.optInt("format_id") == 5) {
                        setOf("audio/mpeg", "audio/mp3")
                    } else {
                        setOf("audio/flac", "audio/x-flac")
                    }
                    )
            ) {
                throw QobuzApiException(200, QobuzFailureReason.Format, QobuzRequestStage.Stream)
            }

            return QobuzStream(
                track = track,
                url = url.toString(),
                formatId = response.getInt("format_id"),
                sampleRate = response.optDouble("sampling_rate", 0.0),
                bitDepth = response.optInt("bit_depth"),
            )
        }
        throw QobuzApiException(403)
    }

    private fun signedStreamRequest(
        credentials: QobuzCredentials,
        trackId: Long,
        formatId: Int,
    ): JSONObject = request("track/getFileUrl", credentials, streamParameters(credentials, trackId, formatId))

    private fun streamParameters(
        credentials: QobuzCredentials,
        trackId: Long,
        formatId: Int,
    ): Map<String, String> {
        val timestamp = BigDecimal.valueOf(System.currentTimeMillis() / 1000.0).toPlainString()

        return mapOf(
            "track_id" to trackId.toString(),
            "format_id" to formatId.toString(),
            "intent" to "stream",
            "request_ts" to timestamp,
            "request_sig" to signature(trackId, formatId, timestamp, credentials.appSecret),
        )
    }

    private fun request(
        endpoint: String,
        credentials: QobuzCredentials,
        parameters: Map<String, String> = emptyMap(),
    ): JSONObject = client.executeSourceRequest(buildRequest(endpoint, credentials, parameters)) { response ->
        parseResponse(response, requestStage(endpoint))
    }

    private suspend fun requestCancellable(
        endpoint: String,
        credentials: QobuzCredentials,
        parameters: Map<String, String> = emptyMap(),
    ): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(buildRequest(endpoint, credentials, parameters))
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                val result =
                    runCatching { response.use { parseResponse(it, requestStage(endpoint)) } }

                if (continuation.isActive) continuation.resumeWith(result)
            }
        })
    }

    private fun requestStage(endpoint: String): QobuzRequestStage = when (endpoint) {
        "user/get" -> QobuzRequestStage.Account
        "track/search" -> QobuzRequestStage.Search
        else -> QobuzRequestStage.Stream
    }

    private fun buildRequest(
        endpoint: String,
        credentials: QobuzCredentials,
        parameters: Map<String, String>,
    ): Request {
        val url = "https://www.qobuz.com/api.json/0.2/$endpoint".toHttpUrl().newBuilder().apply {
            addQueryParameter("app_id", credentials.appId)
            parameters.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()

        return Request.Builder().url(url)
            .header("X-App-Id", credentials.appId)
            .header("X-User-Auth-Token", credentials.userToken)
            .build()
    }

    private fun parseResponse(response: Response, stage: QobuzRequestStage): JSONObject {
        val body = response.body
        val source = body.source()

        if (body.contentLength() > MAX_RESPONSE_BYTES || source.request(MAX_RESPONSE_BYTES + 1L)) {
            throw QobuzApiException(response.code, QobuzFailureReason.Format, stage)
        }

        val text = source.readString(body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
        val result = runCatching { JSONObject(text) }.getOrNull()

        if (!response.isSuccessful || result?.optString("status") == "error") {
            val code = if (response.isSuccessful) {
                result?.optInt(
                    "code",
                    502,
                ) ?: 502
            } else {
                response.code
            }

            val message = listOfNotNull(
                result?.optString("message"),
                result?.optString("error"),
                result?.optString("error_description"),
            ).joinToString(" ")
            throw QobuzApiException(code, qobuzFailureReason(code, message), stage)
        }

        return result ?: throw QobuzApiException(response.code, QobuzFailureReason.Format, stage)
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 2_000_000L
        private const val SIGNATURE_TEST_TRACK_ID = 19512574L

        fun signature(trackId: Long, formatId: Int, timestamp: String, secret: String): String = MessageDigest.getInstance("MD5")
            .digest(
                "trackgetFileUrlformat_id${formatId}intentstreamtrack_id${trackId}${timestamp}$secret".toByteArray(
                    Charsets.UTF_8,
                ),
            )
            .joinToString("") { "%02x".format(it) }
    }
}

internal class QobuzApiException(
    val statusCode: Int,
    val reason: QobuzFailureReason = QobuzFailureReason.Rejected,
    val stage: QobuzRequestStage? = null,
) : IOException("Qobuz request failed ($statusCode, $stage, $reason)") {
    val failure: QobuzFailure get() = QobuzFailure(reason, stage, statusCode.takeIf { it != 200 })
}
