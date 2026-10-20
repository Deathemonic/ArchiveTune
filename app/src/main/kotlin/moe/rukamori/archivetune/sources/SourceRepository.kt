package moe.rukamori.archivetune.sources

import androidx.annotation.WorkerThread
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface SourceRepository {
    val configuration: Flow<SourceConfiguration>
    val settings: Flow<SourcePlaybackSettings>
    val playback: StateFlow<Map<String, SourcePlaybackState>>
    val selectableProviders: Set<SourceProvider>
    val enabled: Boolean

    @WorkerThread
    fun resolve(request: SourceRequest): ResolvedSource?

    suspend fun preload(request: SourceRequest): Boolean

    fun observeMatches(provider: SourceProvider): StateFlow<Map<String, SourceTrack>>

    suspend fun searchTracks(provider: SourceProvider, query: String): List<SourceTrack>

    suspend fun selectTrack(provider: SourceProvider, mediaId: String, track: SourceTrack?)

    suspend fun setPriority(priority: List<SourceProvider>)

    suspend fun setYouTubeFallback(allowed: Boolean)
}
