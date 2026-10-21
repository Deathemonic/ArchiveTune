package moe.rukamori.archivetune.sources.deezer

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

class DeezerPlaybackSource @Inject constructor(private val repository: DeezerRepository) : SelectablePlaybackSource {
    override val accountState: SourceAccountState
        get() = repository.settings.value.let {
            SourceAccountState(it.connected, initialized = repository.storageState.value != SourceStorageState.Loading, quality = it.quality.name, matchRevision = it.matchRevision, accountRevision = it.accountRevision)
        }

    override val settings = combine(repository.settings, repository.storageState) { _, _ -> accountState }

    override val matches = repository.matches

    override suspend fun searchTracks(query: String): List<SourceTrack> = repository.searchTracks(query)

    override suspend fun selectTrack(mediaId: String, track: SourceTrack?) = repository.selectTrack(mediaId, track)

    override val provider = SourceProvider.Deezer

    override val enabled: Boolean
        get() = repository.storageState.value == SourceStorageState.Loading || repository.settings.value.connected

    override fun failure(error: IOException): SourceFailure = when ((error as? DeezerException)?.reason) {
        DeezerFailure.Storage -> SourceFailure.Account
        DeezerFailure.Account -> SourceFailure.Account
        DeezerFailure.Subscription -> SourceFailure.Subscription
        DeezerFailure.Unavailable -> SourceFailure.Unavailable
        DeezerFailure.Network, null -> SourceFailure.Network
    }

    override fun resolve(request: SourceRequest): ResolvedSource? = repository.resolve(request)
}
