package moe.rukamori.archivetune.sources.qobuz

import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.combine
import moe.rukamori.archivetune.sources.ResolvedSource
import moe.rukamori.archivetune.sources.SelectablePlaybackSource
import moe.rukamori.archivetune.sources.SourceAccountState
import moe.rukamori.archivetune.sources.SourceFailure
import moe.rukamori.archivetune.sources.SourceProvider
import moe.rukamori.archivetune.sources.SourceRequest
import moe.rukamori.archivetune.sources.SourceStorageState
import moe.rukamori.archivetune.sources.SourceTrack

class QobuzPlaybackSource @Inject constructor(private val repository: QobuzRepository) : SelectablePlaybackSource {
    override val accountState: SourceAccountState
        get() = repository.settings.value.let {
            SourceAccountState(it.connected, initialized = repository.storageState.value != SourceStorageState.Loading, quality = it.quality.name, matchRevision = it.matchRevision, accountRevision = it.accountRevision)
        }

    override val settings = combine(repository.settings, repository.storageState) { _, _ -> accountState }

    override val matches = repository.matches

    override suspend fun searchTracks(query: String): List<SourceTrack> = repository.searchTracks(query)

    override suspend fun selectTrack(mediaId: String, track: SourceTrack?) = repository.selectTrack(mediaId, track)

    override val provider = SourceProvider.Qobuz

    override val enabled: Boolean
        get() = repository.storageState.value == SourceStorageState.Loading || repository.settings.value.connected

    override fun failure(error: IOException): SourceFailure = when ((error as? QobuzApiException)?.reason) {
        QobuzFailureReason.AppId, QobuzFailureReason.Token, QobuzFailureReason.Signature, QobuzFailureReason.Storage -> SourceFailure.Account
        QobuzFailureReason.Subscription -> SourceFailure.Subscription
        QobuzFailureReason.Network, QobuzFailureReason.RateLimit, null -> SourceFailure.Network
        else -> SourceFailure.Unavailable
    }

    override fun resolve(request: SourceRequest): ResolvedSource? {
        val metadata = request.metadata
        val query = QobuzSongQuery(
            metadata.title,
            metadata.artists.map { it.name },
            metadata.album?.title,
            metadata.duration,
            metadata.explicit,
        )

        return repository.resolve(request.mediaId, query)?.toResolvedSource()
    }

    private fun QobuzStream.toResolvedSource(): ResolvedSource = ResolvedSource(
        cacheKey = "qobuz:${track.id}:$formatId",
        url = url,
        mimeType = if (formatId == 5) "audio/mpeg" else "audio/flac",
        bitDepth = bitDepth.takeIf { formatId != 5 && it > 0 },
        sampleRate = (sampleRate * 1000).toInt().takeIf { it > 0 },
        refreshStream = { repository.refresh(this).toResolvedSource() },
    )
}
