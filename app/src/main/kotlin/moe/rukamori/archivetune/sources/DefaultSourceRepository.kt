package moe.rukamori.archivetune.sources

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.di.SourceApplicationScope

@Singleton
class DefaultSourceRepository @Inject constructor(
    private val sources: Set<@JvmSuppressWildcards PlaybackSource>,
    private val preferences: SourceSettingsRepository,
    @SourceApplicationScope private val scope: CoroutineScope,
) : SourceRepository {
    private data class ResolutionKey(val request: SourceRequest, val settings: SourcePlaybackSettings)
    private data class Resolution(val stream: ResolvedSource?, val state: SourcePlaybackState, val createdAtMs: Long = System.currentTimeMillis())

    private val selectionMutex = Mutex()
    private val prepared = ConcurrentHashMap<ResolutionKey, Resolution>()
    private val inFlight = ConcurrentHashMap<ResolutionKey, CompletableFuture<Resolution>>()

    override val selectableProviders = sources.filterIsInstance<SelectablePlaybackSource>().map { it.provider }.toSet()

    override val configuration = preferences.configuration

    private val accounts = if (sources.isEmpty()) {
        flowOf(emptyMap<SourceProvider, SourceAccountState>())
    } else {
        combine(sources.map { source -> source.settings.map { source.provider to it } }) { it.toMap() }
    }

    override val settings = combine(accounts, configuration, ::SourcePlaybackSettings)

    private fun awaitResolution(request: SourceRequest): Resolution {
        val pending = CompletableFuture<Resolution>()
        val job = scope.launch(Dispatchers.IO) {
            try {
                val settings = settings.first { it.accounts.values.all { account -> account.initialized } }
                val key = ResolutionKey(request, settings.copy(accounts = sources.associate { it.provider to it.accountState }))
                pending.complete(runSourceOperation { prepare(key).also { prepared.remove(key) } })
            } catch (cancellation: CancellationException) {
                pending.completeExceptionally(cancellation)
                throw cancellation
            } catch (failure: Exception) {
                pending.completeExceptionally(failure)
            }
        }
        job.invokeOnCompletion { failure ->
            if (failure != null) pending.completeExceptionally(failure)
        }
        return try {
            pending.get()
        } catch (failure: ExecutionException) {
            throw IOException("Unable to resolve source", failure.cause)
        } catch (interrupted: InterruptedException) {
            job.cancel()
            Thread.currentThread().interrupt()
            throw IOException("Source resolution interrupted", interrupted)
        }
    }

    private val mutablePlayback = MutableStateFlow<Map<String, SourcePlaybackState>>(emptyMap())
    override val playback = mutablePlayback.asStateFlow()

    override val enabled: Boolean
        get() = sources.any { it.enabled }

    override fun resolve(request: SourceRequest): ResolvedSource? {
        setPlayback(request.mediaId, SourcePlaybackState(SourcePlaybackStatus.Resolving))

        val resolution = try {
            awaitResolution(request)
        } catch (error: Exception) {
            setPlayback(request.mediaId, SourcePlaybackState(SourcePlaybackStatus.Unavailable))
            throw (error as? IOException ?: IOException("Unable to resolve source", error))
        }

        setPlayback(request.mediaId, resolution.state)

        return resolution.availableStream()
    }

    override suspend fun preload(request: SourceRequest): Boolean = withContext(Dispatchers.IO) {
        val settings = settings.first { it.accounts.values.all { account -> account.initialized } }

        runSourceOperation { prepare(ResolutionKey(request, settings), cacheResult = true).availableStream() != null }
    }

    private fun Resolution.availableStream(): ResolvedSource? {
        if (state.status == SourcePlaybackStatus.Unavailable) throw IOException("No preferred streaming source is available")

        return stream
    }

    private fun prepare(key: ResolutionKey, cacheResult: Boolean = false): Resolution {
        val now = System.currentTimeMillis()

        prepared.entries.removeIf { now - it.value.createdAtMs >= 60_000L }
        prepared[key]?.let { return it }

        val pending = CompletableFuture<Resolution>()
        val existing = inFlight.putIfAbsent(key, pending)

        if (existing != null) {
            return try {
                existing.get().also { if (cacheResult) prepared[key] = it }
            } catch (error: ExecutionException) {
                if (error.cause is InterruptedIOException && !Thread.currentThread().isInterrupted) {
                    inFlight.remove(key, existing)
                    return prepare(key, cacheResult)
                }
                throw (error.cause as? IOException ?: IOException(error.cause))
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException(error)
            }
        }

        try {
            val resolution = resolveSources(key.request, key.settings.configuration)

            if (cacheResult) {
                if (prepared.size >= 32) prepared.keys.firstOrNull()?.let(prepared::remove)
                prepared[key] = resolution
            }
            pending.complete(resolution)

            return resolution
        } catch (error: Exception) {
            pending.completeExceptionally(error)
            throw error
        } finally {
            inFlight.remove(key, pending)
        }
    }

    private fun resolveSources(request: SourceRequest, settings: SourceConfiguration): Resolution {
        val activeSources = sources.filter { it.enabled }.sortedWith(
            compareBy<PlaybackSource> { (it as? SelectablePlaybackSource)?.matches?.value?.containsKey(request.mediaId) != true }
                .thenBy { settings.priority.indexOf(it.provider).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE },
        )
        val failures = linkedMapOf<SourceProvider, SourceFailure>()

        for (source in activeSources) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Source resolution interrupted")

            try {
                source.resolve(request)?.let {
                    return Resolution(
                        it,
                        SourcePlaybackState(SourcePlaybackStatus.Selected, it.provider, it.mimeType, it.bitDepth, it.sampleRate),
                    )
                }
                failures[source.provider] = SourceFailure.NoMatch
            } catch (failure: IOException) {
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Source resolution interrupted").apply { initCause(failure) }

                failures[source.provider] = source.failure(failure)
            }
        }

        if (activeSources.isNotEmpty() && !settings.youtubeFallback) {
            return Resolution(null, SourcePlaybackState(SourcePlaybackStatus.Unavailable, failures = failures.toMap()))
        }

        return Resolution(null, SourcePlaybackState(SourcePlaybackStatus.YouTube, failures = failures.toMap()))
    }

    private fun selectableSource(provider: SourceProvider): SelectablePlaybackSource = sources
        .filterIsInstance<SelectablePlaybackSource>().first { it.provider == provider }

    override fun observeMatches(provider: SourceProvider) = selectableSource(provider).matches

    override suspend fun searchTracks(provider: SourceProvider, query: String): List<SourceTrack> = selectableSource(provider).searchTracks(query)

    override suspend fun selectTrack(provider: SourceProvider, mediaId: String, track: SourceTrack?) = selectionMutex.withLock {
        val selected = selectableSource(provider)

        selected.selectTrack(mediaId, track)

        if (track != null) {
            for (source in sources.filterIsInstance<SelectablePlaybackSource>()) {
                if (source !== selected && mediaId in source.matches.value) source.selectTrack(mediaId, null)
            }
        }
    }

    override suspend fun setPriority(priority: List<SourceProvider>) = preferences.setPriority(priority)

    override suspend fun setYouTubeFallback(allowed: Boolean) = preferences.setYouTubeFallback(allowed)

    private fun setPlayback(mediaId: String, state: SourcePlaybackState) {
        mutablePlayback.update { previous ->
            previous.filterKeys { it != mediaId }.entries.toList().takeLast(31).associate { it.toPair() } + (mediaId to state)
        }
    }
}
