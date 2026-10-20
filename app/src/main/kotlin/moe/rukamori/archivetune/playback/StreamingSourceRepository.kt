package moe.rukamori.archivetune.playback

import dagger.hilt.android.scopes.ViewModelScoped
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.sources.SourcePlaybackState

data class StreamingSourceData(
    val metadata: MediaMetadata?,
    val sourcePlayback: SourcePlaybackState?,
    val externalSourcesEnabled: Boolean,
    val mimeType: String?,
    val sampleRate: Int?,
)

@OptIn(ExperimentalCoroutinesApi::class)
@ViewModelScoped
class StreamingSourceRepository @Inject constructor() {
    private val connection = MutableStateFlow<PlayerConnection?>(null)
    private var attachments = 0

    fun attach(playerConnection: PlayerConnection) {
        attachments = if (connection.value === playerConnection) attachments + 1 else 1
        connection.value = playerConnection
    }

    fun detach(playerConnection: PlayerConnection) {
        if (connection.value !== playerConnection) return
        attachments--

        if (attachments == 0) connection.value = null
    }

    fun observeSource(): Flow<StreamingSourceData?> = connection.flatMapLatest { playerConnection ->
        if (playerConnection == null) {
            flowOf(null)
        } else {
            val repository = playerConnection.service.sourceRepository
            combine(playerConnection.mediaMetadata, repository.settings, repository.playback, playerConnection.currentFormat) { metadata, settings, playback, format ->
                StreamingSourceData(metadata, playback[metadata?.id], settings.connectedProviders.isNotEmpty(), format?.mimeType, format?.sampleRate)
            }
        }
    }
}
