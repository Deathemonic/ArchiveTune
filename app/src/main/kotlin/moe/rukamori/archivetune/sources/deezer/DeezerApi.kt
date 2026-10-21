package moe.rukamori.archivetune.sources.deezer

import java.io.IOException
import kotlinx.coroutines.suspendCancellableCoroutine
import moe.rukamori.archivetune.sources.createSourceHttpClient
import moe.rukamori.archivetune.sources.executeSourceRequest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

internal class DeezerApi(
    private val credentials: DeezerCredentials,
    client: OkHttpClient = createSourceHttpClient(),
) {
    private val client = client.newBuilder()
        .cookieJar(DeezerCookieJar(credentials.arl))
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    @Volatile private var session: Session? = null

    suspend fun validate() {
        val response = executeCancellable(gatewayRequest("deezer.getUserData", JSONObject(), "null"))
        session = parseSession(gatewayResult(response))
    }

    @Synchronized
    fun search(query: String): List<DeezerTrack> {
        val current = ensureSession()
        val result = gateway(
            "search.music",
            JSONObject().put("query", query).put("filter", "ALL").put("output", "TRACK").put("start", 0).put("nb", 50),
            current.csrf,
        )

        return parseTracks(result)
    }

    @Synchronized
    fun stream(track: DeezerTrack, quality: DeezerQuality): DeezerStream {
        val current = ensureSession()

        if ((quality == DeezerQuality.HiFi && !current.lossless) ||
            (quality == DeezerQuality.High && !current.highQuality)
        ) {
            throw DeezerException(DeezerFailure.Subscription)
        }

        val song = gateway("song.getData", JSONObject().put("SNG_ID", track.id.toString()), current.csrf)
        val token = song.optString("TRACK_TOKEN")

        if (song.optLong("SNG_ID") != track.id || token.isBlank()) {
            throw DeezerException(DeezerFailure.Unavailable)
        }

        val formats = JSONArray().put(JSONObject().put("cipher", "BF_CBC_STRIPE").put("format", quality.format))
        val body = JSONObject()
            .put("license_token", current.license)
            .put("media", JSONArray().put(JSONObject().put("type", "FULL").put("formats", formats)))
            .put("track_tokens", JSONArray().put(token))
        val response = execute(
            Request.Builder().url("https://media.deezer.com/v1/get_url")
                .post(body.toString().toRequestBody(JSON)).build(),
        )

        return parseStream(response, track, quality)
    }

    private fun ensureSession(): Session = session?.takeIf { it.expiresAtMs > System.currentTimeMillis() }
        ?: parseSession(gateway("deezer.getUserData", JSONObject(), "null")).also { session = it }

    private fun parseSession(data: JSONObject): Session {
        val user = data.optJSONObject("USER")
        val options = user?.optJSONObject("OPTIONS")
        val csrf = data.optString("checkForm")
        val license = options?.optString("license_token").orEmpty()

        if (user == null || user.optLong("USER_ID") <= 0 || csrf.isBlank() || license.isBlank()) {
            throw DeezerException(DeezerFailure.Account)
        }

        val lossless = options?.optBoolean("web_lossless") == true || options?.optBoolean("mobile_lossless") == true
        val highQuality = options?.optBoolean("web_hq") == true || options?.optBoolean("mobile_hq") == true

        if (!lossless && !highQuality) throw DeezerException(DeezerFailure.Subscription)

        return Session(csrf, license, lossless, highQuality, System.currentTimeMillis() + 600_000L)
    }

    private fun gateway(method: String, body: JSONObject, token: String): JSONObject = gatewayResult(
        execute(gatewayRequest(method, body, token)),
    )

    private fun gatewayRequest(method: String, body: JSONObject, token: String): Request {
        val url = "https://www.deezer.com/ajax/gw-light.php".toHttpUrl().newBuilder()
            .addQueryParameter("api_version", "1.0")
            .addQueryParameter("api_token", token)
            .addQueryParameter("input", "3")
            .addQueryParameter("method", method)
            .build()

        return Request.Builder().url(url)
            .header("Origin", "https://www.deezer.com")
            .header("Referer", "https://www.deezer.com/")
            .header("User-Agent", "Mozilla/5.0")
            .post(body.toString().toRequestBody(JSON)).build()
    }

    private fun gatewayResult(response: JSONObject): JSONObject {
        val error = response.opt("error")

        if ((error is JSONObject && error.length() > 0) || (error is JSONArray && error.length() > 0)) {
            session = null
            throw DeezerException(DeezerFailure.Account)
        }

        return response.optJSONObject("results") ?: throw DeezerException(DeezerFailure.Unavailable)
    }

    private fun execute(request: Request): JSONObject = try {
        client.executeSourceRequest(request, ::readResponse)
    } catch (failure: DeezerException) {
        throw failure
    } catch (_: Exception) {
        throw DeezerException(DeezerFailure.Network)
    }

    private suspend fun executeCancellable(request: Request): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWith(Result.failure(DeezerException(DeezerFailure.Network)))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use(::readResponse) }
                    .recoverCatching { throw it as? DeezerException ?: DeezerException(DeezerFailure.Network) }
                continuation.resumeWith(result)
            }
        })
    }

    private fun readResponse(response: Response): JSONObject {
        if (!response.isSuccessful) {
            throw DeezerException(if (response.code in setOf(401, 403)) DeezerFailure.Account else DeezerFailure.Network)
        }

        val source = response.body.source()

        if (source.request(MAX_RESPONSE_BYTES + 1)) throw DeezerException(DeezerFailure.Network)

        return JSONObject(source.readUtf8())
    }

    private class Session(
        val csrf: String,
        val license: String,
        val lossless: Boolean,
        val highQuality: Boolean,
        val expiresAtMs: Long,
    )

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val MAX_RESPONSE_BYTES = 2L * 1024 * 1024

        internal fun parseTracks(response: JSONObject): List<DeezerTrack> {
            val tracks = response.optJSONArray("data") ?: response.optJSONObject("TRACK")?.optJSONArray("data") ?: return emptyList()

            return (0 until minOf(tracks.length(), 50)).mapNotNull { index ->
                val track = tracks.optJSONObject(index) ?: return@mapNotNull null
                val id = track.optLong("SNG_ID")
                val title = (track.opt("SNG_TITLE") as? String)?.takeIf(String::isNotBlank)

                if (id <= 0L || title == null || track.optInt("DURATION") < 0) return@mapNotNull null

                DeezerTrack(
                    id = id,
                    title = title,
                    version = (track.opt("VERSION") as? String)?.takeUnless { it.isBlank() || it == "null" }.orEmpty(),
                    artist = (track.opt("ART_NAME") as? String)?.takeIf(String::isNotBlank).orEmpty(),
                    album = (track.opt("ALB_TITLE") as? String)?.takeIf(String::isNotBlank).orEmpty(),
                    duration = track.optInt("DURATION"),
                    explicit = track.optInt("EXPLICIT_LYRICS") == 1,
                    streamable = true,
                )
            }
        }

        internal fun parseStream(response: JSONObject, track: DeezerTrack, quality: DeezerQuality): DeezerStream {
            val data = response.optJSONArray("data")?.optJSONObject(0)
            val media = data?.optJSONArray("media")?.optJSONObject(0)
            val url = media?.optJSONArray("sources")?.optJSONObject(0)?.optString("url")?.toHttpUrlOrNull()
            val cipher = media?.optJSONObject("cipher")?.optString("type")

            if (data?.optJSONArray("errors")?.length()?.let { it > 0 } == true ||
                media?.optString("media_type") != "FULL" || media.optString("format") != quality.format ||
                cipher != "BF_CBC_STRIPE" || url == null || !url.isHttps || !url.host.endsWith(".dzcdn.net")
            ) {
                throw DeezerException(DeezerFailure.Unavailable)
            }

            return DeezerStream(track, url.toString(), quality)
        }
    }
}

private class DeezerCookieJar(arl: String) : CookieJar {
    private val cookies = mutableListOf(
        Cookie.Builder().name("arl").value(arl).domain("deezer.com").path("/").secure().httpOnly().build(),
    )

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (url.host != "www.deezer.com") return

        for (cookie in cookies) {
            this.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            this.cookies += cookie
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> = if (url.host == "www.deezer.com") cookies.filter { it.matches(url) } else emptyList()
}
