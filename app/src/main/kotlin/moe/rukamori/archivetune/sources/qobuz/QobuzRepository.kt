package moe.rukamori.archivetune.sources.qobuz

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.di.SourceApplicationScope
import moe.rukamori.archivetune.sources.SourceMatchStore
import moe.rukamori.archivetune.sources.SourceProvider
import moe.rukamori.archivetune.sources.SourceStorageLoader
import moe.rukamori.archivetune.sources.SourceTrack

@Singleton
class QobuzRepository @Inject constructor(
    private val store: QobuzAccountStore,
    private val matchStore: SourceMatchStore,
    @SourceApplicationScope scope: CoroutineScope,
) {
    private val api by lazy { QobuzApi() }

    @Volatile private var credentials: QobuzCredentials? = null
    private val mutableSettings = MutableStateFlow(QobuzSettings())
    val settings = mutableSettings.asStateFlow()
    private val mutableMatches = MutableStateFlow<Map<String, SourceTrack>>(emptyMap())
    val matches = mutableMatches.asStateFlow()
    private val mutablePlayback = MutableStateFlow<Map<String, QobuzPlaybackState>>(emptyMap())
    val playback = mutablePlayback.asStateFlow()

    private val storage = SourceStorageLoader(
        scope,
        QobuzSettings(),
        store::read,
        { matchStore.read(SourceProvider.Qobuz) },
    ) { account, matches ->
        credentials = account.first
        mutableSettings.value = account.second
        mutableMatches.value = matches
    }
    val storageState = storage.state

    suspend fun awaitInitialization() = storage.awaitInitialization()

    suspend fun connect(appId: String, appSecret: String, userToken: String) = withContext(Dispatchers.IO) {
        storage.awaitInitialization()
        val value = QobuzCredentials(appId, appSecret, userToken)

        api.validate(value)

        synchronized(this@QobuzRepository) {
            val settings = mutableSettings.value.copy(connected = true, accountRevision = mutableSettings.value.accountRevision + 1)

            store.write(value, settings)
            storage.accountUpdated()
            credentials = value
            mutableSettings.value = settings
            mutablePlayback.value = emptyMap()
        }
    }

    suspend fun updateSettings(
        quality: QobuzQuality,
    ) = withContext(Dispatchers.IO) {
        storage.awaitInitialization()
        synchronized(this@QobuzRepository) {
            val credentials = credentials ?: return@synchronized
            val settings = mutableSettings.value.copy(
                quality = quality,
            )

            store.write(credentials, settings)
            storage.accountUpdated()
            mutableSettings.value = settings
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        storage.awaitInitialization()
        synchronized(this@QobuzRepository) {
            store.clear()
            storage.accountUpdated()
            credentials = null
            mutableSettings.value = QobuzSettings()
            mutablePlayback.value = emptyMap()
        }
    }

    suspend fun searchTracks(query: String): List<QobuzTrack> = withContext(Dispatchers.IO) {
        storage.awaitInitialization()
        api.searchTracks(
            credentials ?: throw IOException("Connect Qobuz before searching"),
            query.trim(),
        )
    }

    suspend fun selectTrack(mediaId: String, track: QobuzTrack?) = withContext(Dispatchers.IO) {
        storage.awaitInitialization()
        require(track == null || track.streamable)

        synchronized(this@QobuzRepository) {
            val credentials = credentials

            if (track != null && credentials == null) throw IOException("Connect Qobuz before selecting a track")

            matchStore.write(SourceProvider.Qobuz, mediaId, track)
            mutableMatches.value =
                if (track == null) {
                    mutableMatches.value - mediaId
                } else {
                    mutableMatches.value + (mediaId to track)
                }

            val settings = mutableSettings.value.copy(
                matchRevision = mutableSettings.value.matchRevision + 1,
            )

            mutablePlayback.update { it - mediaId }
            mutableSettings.value = settings
        }
    }

    fun resolve(mediaId: String, query: QobuzSongQuery): QobuzStream? {
        setPlayback(mediaId, QobuzPlaybackState(QobuzPlaybackStatus.Resolving))
        val credentials = credentials

        if (credentials == null) {
            setPlayback(
                mediaId,
                QobuzPlaybackState(
                    QobuzPlaybackStatus.AccountError,
                    failure = QobuzFailure(QobuzFailureReason.Token),
                ),
            )

            throw QobuzApiException(401, QobuzFailureReason.Token, QobuzRequestStage.Account)
        }

        try {
            var track = mutableMatches.value[mediaId]

            if (track == null) {
                val candidates = mutableListOf<QobuzTrack>()

                for (searchQuery in QobuzTrackMatcher.searchQueries(query)) {
                    candidates += api.search(credentials, searchQuery)
                    track = QobuzTrackMatcher.match(query, candidates)

                    if (track != null) break
                }
            }

            if (track == null) {
                setPlayback(mediaId, QobuzPlaybackState(QobuzPlaybackStatus.NoMatch))

                return null
            }

            val stream = api.stream(credentials, track, mutableSettings.value.quality.formats)
            setPlayback(
                mediaId,
                QobuzPlaybackState(
                    if (stream.formatId == 5) {
                        QobuzPlaybackStatus.Lossy
                    } else {
                        QobuzPlaybackStatus.Lossless
                    },
                    track,
                    stream.sampleRate,
                    stream.bitDepth,
                ),
            )

            return stream
        } catch (failure: IOException) {
            if (mutablePlayback.value[mediaId]?.status != QobuzPlaybackStatus.NoMatch) {
                val status = if (failure is QobuzApiException &&
                    failure.reason in
                    setOf(
                        QobuzFailureReason.AppId,
                        QobuzFailureReason.Token,
                        QobuzFailureReason.Signature,
                        QobuzFailureReason.Subscription,
                    )
                ) {
                    QobuzPlaybackStatus.AccountError
                } else {
                    QobuzPlaybackStatus.Unavailable
                }
                setPlayback(
                    mediaId,
                    QobuzPlaybackState(
                        status,
                        failure =
                            (failure as? QobuzApiException)?.failure
                                ?: QobuzFailure(QobuzFailureReason.Network),
                    ),
                )
            }

            throw failure
        }
    }

    fun refresh(stream: QobuzStream): QobuzStream = api.stream(
        credentials ?: throw IOException("Qobuz account disconnected"),
        stream.track,
        listOf(stream.formatId),
    )
        .also {
            if (it.formatId != stream.formatId) {
                throw IOException("Qobuz stream format changed; restart playback")
            }
        }

    private fun setPlayback(mediaId: String, state: QobuzPlaybackState) {
        mutablePlayback.update { previous ->
            previous.filterKeys {
                it != mediaId
            }.entries.toList().takeLast(31).associate { it.toPair() } +
                (mediaId to state)
        }
    }
}
