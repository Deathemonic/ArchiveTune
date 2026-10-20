package moe.rukamori.archivetune.sources.tidal

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
class TidalRepository @Inject constructor(
    private val store: TidalAccountStore,
    private val matchStore: SourceMatchStore,
    @SourceApplicationScope scope: CoroutineScope,
) {
    private val mutableSettings = MutableStateFlow(TidalSettings())

    val settings = mutableSettings.asStateFlow()

    private val mutableMatches = MutableStateFlow<Map<String, SourceTrack>>(emptyMap())
    val matches = mutableMatches.asStateFlow()

    @Volatile private var credentials: TidalCredentials? = null

    @Volatile private var api: TidalApi? = null

    private val oauth by lazy { TidalOAuth() }

    private val storage = SourceStorageLoader(
        scope,
        TidalSettings(),
        store::read,
        { matchStore.read(SourceProvider.Tidal) },
    ) { account, matches ->
        credentials = account.first
        api = account.first?.let { createApi(it) }
        mutableSettings.value = account.second
        mutableMatches.value = matches
    }
    val storageState = storage.state

    suspend fun awaitInitialization() = storage.awaitInitialization()

    internal suspend fun beginLogin(): TidalDeviceAuthorization = withContext(Dispatchers.IO) {
        oauth.authorize(TidalOAuth.client)
    }

    internal suspend fun completeLogin(authorization: TidalDeviceAuthorization) {
        connect(oauth.awaitApproval(TidalOAuth.client, authorization))
    }

    private suspend fun connect(credentials: TidalCredentials) = withContext(Dispatchers.IO) {
        storage.awaitInitialization()
        val api = createApi(credentials)

        runSourceOperation { api.validate() }
        ensureActive()

        synchronized(this@TidalRepository) {
            val settings = mutableSettings.value.copy(connected = true, accountRevision = mutableSettings.value.accountRevision + 1)
            val current = api.currentCredentials

            store.write(current, settings)
            storage.accountUpdated()
            this@TidalRepository.credentials = current
            this@TidalRepository.api = api
            mutableSettings.value = settings
        }
    }

    private fun createApi(credentials: TidalCredentials): TidalApi = TidalApi(credentials) { previous, updated ->
        synchronized(this) {
            if (this.credentials === previous) {
                store.write(updated, mutableSettings.value)
                this.credentials = updated
            }
        }
    }

    suspend fun updateSettings(quality: TidalQuality) = withContext(Dispatchers.IO) {
        storage.awaitInitialization()
        synchronized(this@TidalRepository) {
            val credentials = credentials ?: return@synchronized
            val settings = mutableSettings.value.copy(quality = quality)

            store.write(credentials, settings)
            storage.accountUpdated()
            mutableSettings.value = settings
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        storage.awaitInitialization()
        synchronized(this@TidalRepository) {
            store.clear()
            storage.accountUpdated()
            credentials = null
            api = null
            mutableSettings.value = TidalSettings()
        }
    }

    suspend fun searchTracks(query: String): List<TidalTrack> = withContext(Dispatchers.IO) {
        storage.awaitInitialization()
        val api = api ?: throw TidalException(TidalFailure.Account)
        val tracks = runSourceOperation { api.search(query.trim()) }

        ensureActive()

        if (api !== this@TidalRepository.api) throw TidalException(TidalFailure.Account)

        tracks
    }

    suspend fun selectTrack(mediaId: String, track: TidalTrack?) = withContext(Dispatchers.IO) {
        storage.awaitInitialization()
        require(track == null || track.streamable)

        synchronized(this@TidalRepository) {
            val credentials = credentials

            if (track != null && credentials == null) throw TidalException(TidalFailure.Account)

            matchStore.write(SourceProvider.Tidal, mediaId, track)
            mutableMatches.value = if (track == null) mutableMatches.value - mediaId else mutableMatches.value + (mediaId to track)

            val settings = mutableSettings.value.copy(matchRevision = mutableSettings.value.matchRevision + 1)

            mutableSettings.value = settings
        }
    }

    internal fun resolve(request: SourceRequest): ResolvedSource? {
        val api = api ?: throw TidalException(TidalFailure.Account)
        val quality = settings.value.quality

        mutableMatches.value[request.mediaId]?.let { track ->
            val stream = api.stream(track, quality)

            if (api !== this.api) throw TidalException(TidalFailure.Account)

            return stream.toResolvedSource(api, quality)
        }

        val metadata = request.metadata
        val query = SourceSongQuery(metadata.title, metadata.artists.map { it.name }, metadata.album?.title, metadata.duration, metadata.explicit)
        val candidates = linkedMapOf<Long, TidalTrack>()

        for (search in SourceTrackMatcher.searchQueries(query)) {
            api.search(search).forEach { candidates[it.id] = it }

            val match = SourceTrackMatcher.match(
                query,
                candidates.values.toList(),
            )

            if (match != null) {
                val stream = api.stream(checkNotNull(candidates[match.id]), quality)

                if (api !== this.api) throw TidalException(TidalFailure.Account)

                return stream.toResolvedSource(api, quality)
            }
        }

        return null
    }

    private fun TidalStream.toResolvedSource(api: TidalApi, requestedQuality: TidalQuality): ResolvedSource = ResolvedSource(
        cacheKey = "tidal:${track.id}:$quality",
        url = url,
        mimeType = mimeType,
        provider = SourceProvider.Tidal,
        bitDepth = bitDepth,
        sampleRate = sampleRate,
        dashManifest = dashManifest,
        decorateDataSource = { TidalDataSource(it) },
        supportsCast = dashManifest == null,
        refreshStream = {
            if (api !== this@TidalRepository.api || !settings.value.connected) throw IOException("Tidal account changed")

            api.stream(track, requestedQuality).toResolvedSource(api, requestedQuality)
        },
    )
}
