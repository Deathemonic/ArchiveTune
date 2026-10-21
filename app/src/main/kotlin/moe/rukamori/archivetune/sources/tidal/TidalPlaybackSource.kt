package moe.rukamori.archivetune.sources.tidal

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

class TidalPlaybackSource @Inject constructor(private val repository: TidalRepository) : SelectablePlaybackSource {
    override val accountState: SourceAccountState
        get() = repository.settings.value.let {
            SourceAccountState(it.connected, initialized = repository.storageState.value != SourceStorageState.Loading, quality = it.quality.name, matchRevision = it.matchRevision, accountRevision = it.accountRevision)
        }

    override val settings = combine(repository.settings, repository.storageState) { _, _ -> accountState }

    override val matches = repository.matches

    override suspend fun searchTracks(query: String): List<SourceTrack> = repository.searchTracks(query)

    override suspend fun selectTrack(mediaId: String, track: SourceTrack?) = repository.selectTrack(mediaId, track)

    override fun failure(error: IOException): SourceFailure = when ((error as? TidalException)?.reason) {
        TidalFailure.Storage -> SourceFailure.Account
        TidalFailure.Account, TidalFailure.Expired, TidalFailure.Denied -> SourceFailure.Account
        TidalFailure.Subscription -> SourceFailure.Subscription
        TidalFailure.Unavailable -> SourceFailure.Unavailable
        TidalFailure.Network, null -> SourceFailure.Network
    }

    override val enabled: Boolean
        get() = repository.storageState.value == SourceStorageState.Loading || repository.settings.value.connected

    override val provider = SourceProvider.Tidal

    override fun resolve(request: SourceRequest): ResolvedSource? = repository.resolve(request)
}
