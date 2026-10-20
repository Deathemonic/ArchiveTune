package moe.rukamori.archivetune.sources.tidal

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import java.io.ByteArrayInputStream
import java.util.Base64
import moe.rukamori.archivetune.sources.createSourceHttpClient
import moe.rukamori.archivetune.sources.executeSourceRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

internal class TidalApi(
    private var credentials: TidalCredentials,
    client: OkHttpClient = createSourceHttpClient(),
    private val onCredentialsChanged: (TidalCredentials, TidalCredentials) -> Unit = { _, _ -> },
) {
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private var countryCode: String? = null
    private val oauth = TidalOAuth(client)

    val currentCredentials: TidalCredentials
        @Synchronized get() = credentials

    @Synchronized
    fun validate() {
        val session = request("sessions")
        val country = session.optString("countryCode")

        if (session.optLong("userId") <= 0 || country.length != 2 || !country.all { it in 'A'..'Z' }) {
            throw TidalException(TidalFailure.Account)
        }

        countryCode = country
    }

    @Synchronized
    fun search(query: String): List<TidalTrack> {
        ensureSession()

        val tracks = request("search/tracks", mapOf("query" to query, "limit" to "50")).optJSONArray("items")
            ?: return emptyList()

        return (0 until minOf(tracks.length(), 50)).mapNotNull { index ->
            val track = tracks.optJSONObject(index) ?: return@mapNotNull null
            val id = track.optLong("id")
            val title = (track.opt("title") as? String)?.takeIf(String::isNotBlank)

            if (id <= 0L || title == null || track.optInt("duration") < 0) return@mapNotNull null

            TidalTrack(
                id = id,
                title = title,
                version = (track.opt("version") as? String)?.takeUnless { it.isBlank() || it == "null" }.orEmpty(),
                artist = (track.optJSONObject("artist")?.opt("name") as? String)?.takeIf(String::isNotBlank).orEmpty(),
                album = (track.optJSONObject("album")?.opt("title") as? String)?.takeIf(String::isNotBlank).orEmpty(),
                duration = track.optInt("duration"),
                explicit = track.optBoolean("explicit"),
                streamable = track.optBoolean("allowStreaming", false),
            )
        }
    }

    @Synchronized
    fun stream(track: TidalTrack, quality: TidalQuality): TidalStream = synchronized(playbackLock) {
        ensureSession()

        val response = request(
            "tracks/${track.id}/playbackinfo",
            mapOf(
                "audioquality" to quality.apiValue,
                "playbackmode" to "STREAM",
                "assetpresentation" to "FULL",
                "immersiveaudio" to "false",
            ),
        )

        parseStream(response, track, quality)
    }

    private fun ensureSession() {
        if (countryCode == null) validate()
    }

    private fun request(path: String, parameters: Map<String, String> = emptyMap()): JSONObject {
        val url = "$API_URL/$path".toHttpUrl().newBuilder()

        countryCode?.let { url.addQueryParameter("countryCode", it) }
        parameters.forEach { (name, value) -> url.addQueryParameter(name, value) }

        if (credentials.refreshToken != null && credentials.expiresAtMs > 0 &&
            System.currentTimeMillis() >= credentials.expiresAtMs - 60_000L
        ) {
            refreshCredentials()
        }

        return try {
            for (attempt in 0..1) {
                val request = Request.Builder()
                    .url(url.build())
                    .header("Authorization", "Bearer ${credentials.accessToken}")
                    .header("Accept", "application/json")
                    .build()

                val result = client.executeSourceRequest(request) { response ->
                    if (response.code != 401 || attempt != 0 || credentials.refreshToken == null) {
                        if (!response.isSuccessful) {
                            throw TidalException(
                                when (response.code) {
                                    401 -> TidalFailure.Account
                                    403 -> TidalFailure.Subscription
                                    404 -> TidalFailure.Unavailable
                                    else -> TidalFailure.Network
                                },
                            )
                        }

                        val source = response.body.source()

                        if (source.request(MAX_RESPONSE_BYTES + 1)) throw TidalException(TidalFailure.Network)

                        JSONObject(source.readUtf8())
                    } else {
                        null
                    }
                }

                if (result != null) return result

                refreshCredentials()
            }

            throw TidalException(TidalFailure.Account)
        } catch (failure: TidalException) {
            throw failure
        } catch (_: Exception) {
            throw TidalException(TidalFailure.Network)
        }
    }

    private fun refreshCredentials() {
        val previous = credentials
        val updated = oauth.refresh(previous)

        onCredentialsChanged(previous, updated)
        credentials = updated
    }

    companion object {
        private val playbackLock = Any()
        private const val API_URL = "https://api.tidal.com/v1"
        private const val MAX_RESPONSE_BYTES = 2L * 1024 * 1024
        private val losslessQualities = setOf("LOSSLESS", "HI_RES_LOSSLESS")
        private val unsafeXml = Regex("<!\\s*(?:DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE)

        internal fun parseStream(response: JSONObject, track: TidalTrack, quality: TidalQuality): TidalStream = try {
            val actualQuality = response.optString("audioQuality")
            val lossless = actualQuality in losslessQualities

            if (response.optLong("trackId") != track.id || response.optString("assetPresentation") != "FULL" ||
                response.optString("audioMode") != "STEREO" ||
                actualQuality !in losslessQualities + setOf("LOW", "HIGH") ||
                (quality != TidalQuality.Low && !lossless)
            ) {
                throw TidalException(TidalFailure.Unavailable)
            }

            val bytes = Base64.getDecoder().decode(response.getString("manifest"))
            val bitDepth = response.optInt("bitDepth").takeIf { lossless && it in setOf(16, 24, 32) }
            val sampleRate = response.optInt("sampleRate").takeIf { it in 8000..384000 }

            when (response.optString("manifestMimeType")) {
                "application/vnd.tidal.bts" -> {
                    val manifest = JSONObject(String(bytes, Charsets.UTF_8))
                    val mimeType = if (lossless) MimeTypes.AUDIO_FLAC else MimeTypes.AUDIO_AAC
                    val codec = manifest.optString("codecs").lowercase()
                    val urls = manifest.optJSONArray("urls")
                    val url = urls?.optString(0).orEmpty()

                    if (manifest.optString("encryptionType") != "NONE" || urls?.length() != 1 || !isMediaUrl(url) ||
                        (lossless && codec != "flac") || (!lossless && codec != "aac" && !codec.startsWith("mp4a"))
                    ) {
                        throw TidalException(TidalFailure.Unavailable)
                    }

                    TidalStream(track, url, mimeType, actualQuality, bitDepth, sampleRate)
                }

                MimeTypes.APPLICATION_MPD -> {
                    if (unsafeXml.containsMatchIn(String(bytes, Charsets.UTF_8))) throw TidalException(TidalFailure.Unavailable)

                    val manifest = DashManifestParser().parse(Uri.parse("$API_URL/"), ByteArrayInputStream(bytes))

                    if (manifest.dynamic || manifest.periodCount != 1) throw TidalException(TidalFailure.Unavailable)

                    val adaptationSets = manifest.getPeriod(0).adaptationSets
                    val representations = adaptationSets.flatMap { it.representations }
                    val mimeType = if (lossless) MimeTypes.AUDIO_FLAC else MimeTypes.AUDIO_AAC

                    if (adaptationSets.any { it.type != C.TRACK_TYPE_AUDIO } || representations.isEmpty() ||
                        representations.any { it.format.drmInitData != null || it.format.sampleMimeType != mimeType }
                    ) {
                        throw TidalException(TidalFailure.Unavailable)
                    }

                    TidalStream(track, "$API_URL/", mimeType, actualQuality, bitDepth, sampleRate, manifest)
                }

                else -> throw TidalException(TidalFailure.Unavailable)
            }
        } catch (failure: TidalException) {
            throw failure
        } catch (_: Exception) {
            throw TidalException(TidalFailure.Unavailable)
        }

        internal fun isMediaUrl(value: String): Boolean {
            val url = value.toHttpUrlOrNull() ?: return false

            return url.isHttps && url.username.isEmpty() && url.password.isEmpty() &&
                (url.host.endsWith(".tidal.com") || url.host.endsWith(".tidalhifi.com"))
        }
    }
}
