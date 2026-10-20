package moe.rukamori.archivetune.domain.sources

import javax.inject.Inject
import kotlinx.coroutines.flow.map
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.sources.SourceProvider
import moe.rukamori.archivetune.sources.SourceRepository
import moe.rukamori.archivetune.sources.SourceTrack

data class SourceProviderOptions(val providers: List<SourceProvider>, val initialized: Boolean)

class ChooseSourceTrackUseCase @Inject constructor(private val repository: SourceRepository) {
    val providerOptions = repository.settings.map { settings ->
        SourceProviderOptions(
            settings.configuration.priority.filter { it in settings.connectedProviders && it in repository.selectableProviders },
            settings.accounts.values.all { it.initialized },
        )
    }

    fun initialQuery(metadata: MediaMetadata): String = "${metadata.artists.firstOrNull()?.name.orEmpty()} ${metadata.title}".trim()
    fun preferredProvider(mediaId: String): SourceProvider? = repository.playback.value[mediaId]?.provider
    fun observeSelections(provider: SourceProvider) = repository.observeMatches(provider).map { matches -> matches.mapValues { it.value.id } }
    suspend fun search(provider: SourceProvider, query: String) = repository.searchTracks(provider, query.trim())

    suspend fun select(provider: SourceProvider, mediaId: String, track: SourceTrack?) {
        require(track == null || track.streamable)
        repository.selectTrack(provider, mediaId, track)
    }
}
