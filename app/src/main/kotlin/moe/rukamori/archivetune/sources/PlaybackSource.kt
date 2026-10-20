package moe.rukamori.archivetune.sources

import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.dash.manifest.DashManifest
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import moe.rukamori.archivetune.models.MediaMetadata

data class SourceRequest(
    val mediaId: String,
    val metadata: MediaMetadata,
)

class ResolvedSource(
    val cacheKey: String,
    val url: String,
    val mimeType: String,
    val provider: SourceProvider = SourceProvider.Qobuz,
    val bitDepth: Int? = null,
    val sampleRate: Int? = null,
    val decorateDataSource: (DataSource) -> DataSource = { it },
    val supportsCast: Boolean = true,
    val dashManifest: DashManifest? = null,
    private val refreshStream: () -> ResolvedSource,
) {
    fun refresh(): ResolvedSource = refreshStream().also { refreshed ->
        if (refreshed.cacheKey != cacheKey || refreshed.provider != provider || refreshed.mimeType != mimeType ||
            (bitDepth != null && refreshed.bitDepth != null && refreshed.bitDepth != bitDepth) ||
            (sampleRate != null && refreshed.sampleRate != null && refreshed.sampleRate != sampleRate) ||
            (refreshed.dashManifest == null) != (dashManifest == null)
        ) {
            throw IOException("Source representation changed; restart playback")
        }
    }

    override fun toString(): String = "ResolvedSource(redacted)"
}

interface PlaybackSource {
    val enabled: Boolean
    val settings: Flow<SourceAccountState>
    val accountState: SourceAccountState
    val provider: SourceProvider

    fun resolve(request: SourceRequest): ResolvedSource?

    fun failure(error: IOException): SourceFailure = SourceFailure.Unavailable
}

interface SelectablePlaybackSource : PlaybackSource {
    val matches: StateFlow<Map<String, SourceTrack>>

    suspend fun searchTracks(query: String): List<SourceTrack>

    suspend fun selectTrack(mediaId: String, track: SourceTrack?)
}

enum class SourceFailure {
    NoMatch,
    Account,
    Subscription,
    Unavailable,
    Network,
}

enum class SourceProvider {
    Qobuz,
    Deezer,
    Tidal,
}

enum class SourcePlaybackStatus {
    Resolving,
    Selected,
    YouTube,
    Unavailable,
}

data class SourcePlaybackState(
    val status: SourcePlaybackStatus,
    val provider: SourceProvider? = null,
    val mimeType: String? = null,
    val bitDepth: Int? = null,
    val sampleRate: Int? = null,
    val failures: Map<SourceProvider, SourceFailure> = emptyMap(),
)
