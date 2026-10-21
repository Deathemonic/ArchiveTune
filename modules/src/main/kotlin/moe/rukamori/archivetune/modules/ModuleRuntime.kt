package moe.rukamori.archivetune.modules

import android.content.Context
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.evaluate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

class ModuleRuntime(context: Context, private val http: ModuleHttpClient) {
    private val applicationContext = context.applicationContext
    private val lock = Mutex()
    private val engines = LinkedHashMap<String, Engine>(8, 0.75f, true)
    private data class Engine(val source: String, val runtime: QuickJs)

    suspend fun inspect(source: String, origin: String): ModuleManifest =
        withTimeoutOrNull(5_000) {
            lock.withLock {
                val runtime = create(source, origin)
                try { manifest(runtime) } finally { runtime.close() }
            }
        } ?: throw ModuleException(ModuleFailure.TIMEOUT)

    suspend fun search(id: String, source: String, origin: String, query: String, settings: Map<String, String>): List<ModuleTrack> {
        val result = call(id, source, origin, "searchTracks", JSONArray().put(query).put(25).put(context(settings)))
        val tracks = (result as? JSONObject)?.optJSONArray("tracks") ?: result as? JSONArray ?: JSONArray()
        return (0 until minOf(tracks.length(), 100)).mapNotNull { index ->
            val track = tracks.optJSONObject(index) ?: return@mapNotNull null
            val trackId = track.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val artist = track.opt("artists") ?: track.opt("artist")
            val artists = when (artist) {
                is JSONArray -> (0 until artist.length()).mapNotNull { position ->
                    val item = artist.opt(position)
                    (if (item is JSONObject) item.optString("name") else item?.toString())?.takeIf(String::isNotBlank)
                }
                is JSONObject -> listOfNotNull(artist.optString("name").takeIf(String::isNotBlank))
                else -> listOfNotNull(artist?.toString()?.takeIf(String::isNotBlank))
            }
            ModuleTrack(trackId, track.optString("title"), artists, track.optString("album").takeIf(String::isNotBlank),
                track.optInt("duration").takeIf { it > 0 },
                if (track.has("explicit") && !track.isNull("explicit")) track.optBoolean("explicit") else null,
                track.optString("isrc").takeIf(String::isNotBlank))
        }
    }

    suspend fun stream(id: String, source: String, origin: String, trackId: String, settings: Map<String, String>): ModuleStream {
        val quality = settings["quality"]?.let(::parseJsonValue) ?: JSONObject.NULL
        val value = call(id, source, origin, "getTrackStreamUrl", JSONArray().put(trackId).put(quality).put(context(settings))) as? JSONObject
            ?: throw ModuleException(ModuleFailure.EXECUTION)
        val url = ModuleHttpClient.webUrl(value.optString("streamUrl", value.optString("url")))
        val track = value.optJSONObject("track") ?: JSONObject()
        val headers = value.optJSONObject("headers") ?: value.optJSONObject("requestHeaders") ?: track.optJSONObject("headers") ?: JSONObject()
        val mime = value.optString("mimeType", track.optString("mimeType")).ifBlank {
            when (URI(url).path.substringAfterLast('.').lowercase(java.util.Locale.ROOT)) {
                "m3u8" -> "application/x-mpegURL"
                "mpd" -> "application/dash+xml"
                "flac" -> "audio/flac"
                "m4a", "mp4" -> "audio/mp4"
                "opus", "ogg" -> "audio/ogg"
                "wav" -> "audio/wav"
                "aac" -> "audio/aac"
                "mp3" -> "audio/mpeg"
                else -> "application/octet-stream"
            }
        }
        val now = System.currentTimeMillis()
        val lifetime = value.optLong("expiresInSeconds", value.optLong("expiresIn", 300)).coerceIn(1, 86_400)
        val expiry = value.optLong("expiresAtMs", value.optLong("expiresAt", 0)).let {
            when { it <= 0 -> now + lifetime * 1000; it < 10_000_000_000L -> it * 1000; else -> it }
        }
        if (expiry <= System.currentTimeMillis()) throw ModuleException(ModuleFailure.EXECUTION)
        return ModuleStream(url, headers.keys().asSequence().associateWith(headers::getString), mime,
            value.optString("codecs", track.optString("codecs")), track.optInt("bitrate", value.optInt("bitrate")),
            track.optDouble("sampleRate").takeIf { it.isFinite() && it > 0 }?.let { if (it < 1000) (it * 1000).toInt() else it.toInt() },
            value.optLong("contentLength", track.optLong("contentLength", 0)), expiry,
            value.optString("variant", track.optString("audioQuality", track.optString("format", mime))))
    }

    suspend fun unload(id: String) {
        lock.withLock { engines.keys.filter { it == id || it.startsWith("$id:") }.forEach { engines.remove(it)?.runtime?.close() } }
    }

    private suspend fun call(id: String, source: String, origin: String, function: String, args: JSONArray): Any =
        lock.withLock {
            var engine = engines[id]
            if (engine?.source != source) {
                engines.remove(id)?.runtime?.close()
                val runtime = create(source, origin)
                engine = Engine(source, runtime)
                engines[id] = engine
                while (engines.size > 2) engines.remove(engines.keys.first())?.runtime?.close()
            }
            val runtime = checkNotNull(engine).runtime
            try {
                val result = runtime.evaluate<String>(
                    "JSON.stringify(await (async()=>{const args=$args;" +
                        (if (function == "getTrackStreamUrl") "if(args[1]===null)args[1]=undefined;" else "") +
                        "return globalThis.__provider[${JSONObject.quote(function)}](...args);})());",
                )
                if (result.length > ModuleHttpClient.MAX_BODY_BYTES) throw ModuleException(ModuleFailure.EXECUTION)
                org.json.JSONTokener(result).nextValue()
            } catch (error: CancellationException) {
                engines.remove(id)?.runtime?.close()
                throw error
            } catch (error: Exception) {
                engines.remove(id)?.runtime?.close()
                throw ModuleException(ModuleFailure.EXECUTION, error)
            }
        }

    private suspend fun create(source: String, origin: String): QuickJs = withContext(Dispatchers.Default) {
        val runtime = QuickJs.create(Dispatchers.Default)
        try {
            runtime.memoryLimit = 32L * 1024 * 1024
            runtime.maxStackSize = 1024L * 1024
            runtime.evaluationTimeoutMillis = 15_000
            val namespace = java.util.UUID.randomUUID().toString()
            runtime.asyncFunction<String, String>("__moduleFetch") { json -> http.fetch(json, origin, namespace) }
            val timers = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Unit>>()
            runtime.asyncFunction<String, Unit>("__moduleDelay") { json ->
                val value = JSONObject(json)
                val id = value.getString("id")
                val signal = CompletableDeferred<Unit>()
                timers[id] = signal
                try { withTimeoutOrNull(value.optLong("millis").coerceIn(0, 15_000)) { signal.await() }; Unit }
                finally { timers.remove(id, signal) }
            }
            runtime.function("__moduleClearTimer") { args -> args.firstOrNull()?.toString()?.let { timers.remove(it)?.complete(Unit) }; Unit }
            runtime.function("__moduleAbort") { args -> args.firstOrNull()?.toString()?.let { http.abort("$namespace:$it") }; Unit }
            runtime.function("__moduleUrl") { args ->
                val input = args.firstOrNull()?.toString().orEmpty()
                val base = args.getOrNull(1)?.toString()?.takeUnless { it == "undefined" } ?: origin
                val uri = URI(ModuleHttpClient.resolveUrl(input, base))
                JSONObject().put("href", uri.toString()).put("protocol", "${uri.scheme}:")
                    .put("hostname", uri.host.orEmpty()).put("host", uri.rawAuthority.orEmpty())
                    .put("pathname", uri.rawPath.orEmpty().ifBlank { "/" }).put("search", uri.rawQuery?.let { "?$it" }.orEmpty())
                    .put("hash", uri.rawFragment?.let { "#$it" }.orEmpty()).put("origin", "${uri.scheme}://${uri.rawAuthority}").toString()
            }
            runtime.evaluate<Unit>(applicationContext.assets.open("module-host.js").bufferedReader().use { it.readText() })
            val code = ModuleSourceDecoder.decode(source).replaceFirst(Regex("^export\\s+default\\s+"), "return ")
            runtime.evaluate<Unit>(
                "globalThis.__provider=(function(){const module={exports:{}};const exports=module.exports;" +
                    code + "\n;return module.exports;})();",
            )
            manifest(runtime)
            runtime
        } catch (error: Throwable) { runtime.close(); throw error }
    }

    private suspend fun manifest(runtime: QuickJs): ModuleManifest {
        val manifest = runtime.evaluate<String>(
            "JSON.stringify((()=>{const p=globalThis.__provider;if(!p||typeof p!=='object')return {};" +
                "return {id:p.id,name:p.name,version:p.version,author:p.author,description:p.description,labels:p.labels," +
                "settings:p.settings||{},capabilities:['searchTracks','getTrackStreamUrl','getAlbum','getArtist','getPlaylist'].filter(k=>typeof p[k]==='function')};})());",
        )
        return ModuleManifest.parse(JSONObject(manifest))
    }

    private fun context(settings: Map<String, String>): JSONObject = JSONObject().put("settings", JSONObject().apply {
        settings.forEach { (key, value) ->
            put(key, JSONObject().put("value", parseJsonValue(value)))
        }
    })
}
