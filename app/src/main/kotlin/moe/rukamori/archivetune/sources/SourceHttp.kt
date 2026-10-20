package moe.rukamori.archivetune.sources

import java.io.IOException
import java.io.InterruptedIOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import moe.rukamori.archivetune.innertube.YouTube
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal fun <T> OkHttpClient.executeSourceRequest(request: Request, read: (Response) -> T): T {
    if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Source request interrupted")

    val call = newCall(request)
    val result = CompletableFuture<T>()
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            result.completeExceptionally(e)
        }

        override fun onResponse(call: Call, response: Response) {
            try {
                result.complete(response.use(read))
            } catch (failure: Exception) {
                result.completeExceptionally(failure)
            }
        }
    })

    return try {
        result.get()
    } catch (interrupted: InterruptedException) {
        call.cancel()
        Thread.currentThread().interrupt()
        throw InterruptedIOException("Source request interrupted").apply { initCause(interrupted) }
    } catch (failure: ExecutionException) {
        throw failure.cause ?: failure
    }
}

internal suspend fun <T> runSourceOperation(block: () -> T): T = try {
    runInterruptible(Dispatchers.IO, block)
} catch (failure: Exception) {
    currentCoroutineContext().ensureActive()
    throw failure
}

internal fun createSourceHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .dns(Dns { hostname -> YouTube.dns.lookup(hostname) })
    .proxySelector(object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> = listOf(YouTube.streamOkHttpProxy)

        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
    })
    .proxyAuthenticator { route, response ->
        val proxy = YouTube.streamOkHttpProxy
        val username = YouTube.proxyUsername
        val password = YouTube.proxyPassword

        if (proxy.type() != Proxy.Type.HTTP || route?.proxy != proxy ||
            username.isNullOrBlank() || password.isNullOrBlank() ||
            response.request.header("Proxy-Authorization") != null
        ) {
            null
        } else {
            response.request.newBuilder()
                .header("Proxy-Authorization", Credentials.basic(username, password))
                .build()
        }
    }
    .build()
