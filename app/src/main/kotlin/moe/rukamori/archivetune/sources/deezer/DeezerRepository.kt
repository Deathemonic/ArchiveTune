package moe.rukamori.archivetune.sources.deezer

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.di.SourceApplicationScope
import moe.rukamori.archivetune.sources.ResolvedSource
import moe.rukamori.archivetune.sources.SourceMatchStore
import moe.rukamori.archivetune.sources.SourceProvider
import moe.rukamori.archivetune.sources.SourceRequest
import moe.rukamori.archivetune.sources.SourceSongQuery
import moe.rukamori.archivetune.sources.SourceStorageLoader
import moe.rukamori.archivetune.sources.SourceTrack
import moe.rukamori.archivetune.sources.SourceTrackMatcher
import moe.rukamori.archivetune.sources.runSourceOperation

@Singleton
class DeezerRepository @Inject constructor(
    private val store: DeezerAccountStore,
    private val matchStore: SourceMatchStore,
    @SourceApplicationScope scope: CoroutineScope,
) {
    private val mutableSettings = MutableStateFlow(DeezerSettings())
    val settings = mutableSettings.asStateFlow()

    private val mutableMatches = MutableStateFlow<Map<String, SourceTrack>>(emptyMap())
    val matches = mutableMatches.asStateFlow()

    @Volatile private var credentials: DeezerCredentials? = null

    @Volatile private var api: DeezerApi? = null

    private val storage = SourceStorageLoader(
        scope,
        DeezerSettings(),
        store::read,
        { matchStore.read(SourceProvider.Deezer) },
    ) { account, matches ->
        credentials = account.first
        api = account.first?.let { DeezerApi(it) }
        mutableSettings.value = account.second
        mutableMatches.value = matches
    }
    val storageState = storage.state

    suspend fun awaitInitialization() = storage.awaitInitialization()

    suspend fun connect(token: String) = withContext(Dispatchers.IO) {
        storage.awaitInitialization()
        val credentials = DeezerCredentials(token)
        val api = DeezerApi(credentials)

        api.validate()
        ensureActive()

        synchronized(this@DeezerRepository) {
            val settings = mutableSettings.value.copy(connected = true, accountRevision = mutableSettings.value.accountRevision + 1)
            store.write(credentials, settings)
            storage.accountUpdated()
            this@DeezerRepository.credentials = credentials
            this@DeezerRepository.api = api
            mutableSettings.value = settings
        }
    }

    suspend fun updateSettings(
        quality: DeezerQuality,
    ) = withContext(Dispatchers.IO) {
        storage.awaitInitialization()
        synchronized(this@DeezerRepository) {
            val credentials = credentials ?: return@synchronized
            val settings = mutableSettings.value.copy(quality = quality)
            store.write(credentials, settings)
            storage.accountUpdated()
            mutableSettings.value = settings
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        storage.awaitInitialization()
        synchronized(this@DeezerRepository) {
            store.clear()
            storage.accountUpdated()
            credentials = null
            api = null
            mutableSettings.value = DeezerSettings()
        }
    }

    suspend fun searchTracks(query: String): List<DeezerTrack> = withContext(Dispatchers.IO) {
        storage.awaitInitialization()
        val api = api ?: throw DeezerException(DeezerFailure.Account)
        val tracks = runSourceOperation { api.search(query.trim()) }

        ensureActive()

        if (api !== this@DeezerRepository.api) throw DeezerException(DeezerFailure.Account)

        tracks
    }

    suspend fun selectTrack(mediaId: String, track: DeezerTrack?) = withContext(Dispatchers.IO) {
        storage.awaitInitialization()
        require(track == null || track.streamable)

        synchronized(this@DeezerRepository) {
            val credentials = credentials

            if (track != null && credentials == null) throw DeezerException(DeezerFailure.Account)

            matchStore.write(SourceProvider.Deezer, mediaId, track)
            mutableMatches.value = if (track == null) mutableMatches.value - mediaId else mutableMatches.value + (mediaId to track)

            val settings = mutableSettings.value.copy(matchRevision = mutableSettings.value.matchRevision + 1)

            mutableSettings.value = settings
        }
    }

    internal fun resolve(request: SourceRequest): ResolvedSource? {
        val (api, account) = synchronized(this) {
            (api ?: throw DeezerException(DeezerFailure.Account)) to credentials
        }
        mutableMatches.value[request.mediaId]?.let { track ->
            val stream = api.stream(track, settings.value.quality)

            if (account !== credentials) throw DeezerException(DeezerFailure.Account)

            return stream.toResolvedSource(api, account)
        }

        val metadata = request.metadata
        val query = SourceSongQuery(metadata.title, metadata.artists.map { it.name }, metadata.album?.title, metadata.duration, metadata.explicit)
        val candidates = linkedMapOf<Long, DeezerTrack>()
        val unavailable = mutableSetOf<Long>()
        var streamFailure: DeezerException? = null

        for (search in SourceTrackMatcher.searchQueries(query)) {
            api.search(search).forEach { candidates[it.id] = it }

            while (true) {
                val match = SourceTrackMatcher.match(query, candidates.values.filter { it.id !in unavailable }) ?: break

                try {
                    val stream = api.stream(match, settings.value.quality)

                    if (account !== credentials) throw DeezerException(DeezerFailure.Account)

                    return stream.toResolvedSource(api, account)
                } catch (failure: DeezerException) {
                    if (failure.reason != DeezerFailure.Unavailable) throw failure

                    unavailable += match.id
                    streamFailure = failure

                    if (unavailable.size >= 3) throw failure
                }
            }
        }

        streamFailure?.let { throw it }

        return null
    }

    private fun DeezerStream.toResolvedSource(api: DeezerApi, account: DeezerCredentials?): ResolvedSource = ResolvedSource(
        cacheKey = "deezer:${track.id}:${quality.format}",
        url = url,
        mimeType = quality.mimeType,
        provider = SourceProvider.Deezer,
        bitDepth = if (quality == DeezerQuality.HiFi) 16 else null,
        sampleRate = null,
        decorateDataSource = { DeezerDataSource(it, track.id) },
        supportsCast = false,
        refreshStream = {
            if (account !== credentials || !settings.value.connected) throw IOException("Deezer account changed")

            api.stream(track, quality).toResolvedSource(api, account)
        },
    )
}
