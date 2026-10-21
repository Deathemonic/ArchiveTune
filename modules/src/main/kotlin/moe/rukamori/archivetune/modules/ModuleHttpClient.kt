package moe.rukamori.archivetune.modules

import android.util.Base64
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ModuleHttpClient(configuredClient: OkHttpClient? = null) {
    private val calls = ConcurrentHashMap<String, Call>()
    private val callLock = Any()
    private val abortedCalls = LinkedHashSet<String>()
    private val client = (configuredClient ?: OkHttpClient()).newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val initial = chain.call().request().url
            val redirected = if (request.url.host != initial.host || request.url.port != initial.port || request.url.scheme != initial.scheme) {
                request.newBuilder().removeHeader("Authorization").removeHeader("Cookie").removeHeader("Proxy-Authorization").build()
            } else request
            chain.proceed(redirected)
        }.build()

    suspend fun document(url: String): Pair<String, String> {
        val response = execute(Request.Builder().url(webUrl(url)).build(), "document:${java.util.UUID.randomUUID()}", ModuleSourceDecoder.MAX_SOURCE_BYTES)
        if (!response.getBoolean("ok")) throw ModuleException(ModuleFailure.NETWORK)
        return response.getString("text") to response.getString("url")
    }

    suspend fun fetch(json: String, baseUrl: String, namespace: String): String {
        val input = JSONObject(json)
        val id = namespace + ":" + input.getString("id")
        val url = webUrl(resolveUrl(input.getString("url"), baseUrl))
        val method = input.optString("method", "GET").uppercase(java.util.Locale.ROOT)
        val headers = input.optJSONObject("headers") ?: JSONObject()
        val builder = Request.Builder().url(url)
        headers.keys().forEach { key -> builder.header(key, headers.getString(key)) }
        val body = when {
            input.has("bodyBase64") -> Base64.decode(input.getString("bodyBase64"), Base64.DEFAULT)
            input.has("body") && !input.isNull("body") -> input.getString("body").toByteArray()
            else -> null
        }
        if (body != null && body.size > MAX_BODY_BYTES) throw ModuleException(ModuleFailure.INVALID_DOCUMENT)
        val requestBody = if (method in listOf("GET", "HEAD")) null else
            (body ?: byteArrayOf()).toRequestBody(headers.optString("content-type", headers.optString("Content-Type")).toMediaTypeOrNull())
        builder.method(method, requestBody)
        return execute(builder.build(), id, MAX_BODY_BYTES).toString()
    }

    fun abort(id: String) {
        synchronized(callLock) {
            val call = calls[id]
            if (call != null) call.cancel() else {
                abortedCalls.add(id)
                while (abortedCalls.size > 128) abortedCalls.remove(abortedCalls.first())
            }
        }
    }

    private suspend fun execute(request: Request, id: String, maximumBytes: Int): JSONObject =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            val accepted = synchronized(callLock) {
                if (abortedCalls.remove(id) || calls.size >= 8 || calls.containsKey(id)) false else { calls[id] = call; true }
            }
            if (!accepted) {
                continuation.resumeWithException(ModuleException(ModuleFailure.EXECUTION))
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { call.cancel(); calls.remove(id, call) }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    calls.remove(id, call)
                    if (continuation.isActive) continuation.resumeWithException(ModuleException(ModuleFailure.NETWORK, e))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            val body = response.body
                            if (body.contentLength() > maximumBytes) throw ModuleException(ModuleFailure.INVALID_DOCUMENT)
                            val output = java.io.ByteArrayOutputStream()
                            body.byteStream().use { input ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (output.size() + count > maximumBytes) throw ModuleException(ModuleFailure.INVALID_DOCUMENT)
                                    output.write(buffer, 0, count)
                                }
                            }
                            val bytes = output.toByteArray()
                            JSONObject().put("status", response.code).put("ok", response.isSuccessful)
                                .put("url", response.request.url.toString())
                                .put("headers", JSONObject(response.headers.toMap()))
                                .put("text", bytes.toString(Charsets.UTF_8))
                                .put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    } finally { calls.remove(id, call) }
                }
            })
        }

    companion object {
        const val MAX_BODY_BYTES = 8 * 1024 * 1024

        fun webUrl(value: String): String {
            val uri = try { URI(value) } catch (error: Exception) { throw ModuleException(ModuleFailure.INVALID_DOCUMENT, error) }
            if (uri.scheme !in listOf("https", "http") || uri.host.isNullOrBlank() || uri.userInfo != null) {
                throw ModuleException(ModuleFailure.INVALID_DOCUMENT)
            }
            return uri.toASCIIString()
        }

        fun resolveUrl(value: String, base: String): String = try {
            val uri = URI(value)
            if (uri.isAbsolute) uri.toString() else URI(base).resolve(uri).toString()
        } catch (error: Exception) { throw ModuleException(ModuleFailure.INVALID_DOCUMENT, error) }
    }
}
