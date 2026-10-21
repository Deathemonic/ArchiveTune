/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.modules

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.utils.FuzzyMatcher
import moe.rukamori.archivetune.utils.isLocalMediaId
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class ModuleMatchMetadata(
    val title: String,
    val artists: List<String>,
    val album: String?,
    val duration: Int,
    val explicit: Boolean,
    val eligible: Boolean,
)

fun MediaMetadata.moduleMatchMetadata() = ModuleMatchMetadata(title, artists.map { it.name }, album?.title,
    duration, explicit, !id.isLocalMediaId() && !isPodcast && !isMusicVideo)

data class ModuleAudioSelection(
    val originalId: String,
    val module: InstalledModule,
    val trackId: String,
    val stream: ModuleStream,
    val cacheKey: String,
)

enum class ModuleCheckStatus { READY, CONFIGURATION, UNAVAILABLE, FAILED, NO_MATCH, RESOLVED, TIMEOUT }

data class ModuleDiagnostic(val status: ModuleCheckStatus, val atMs: Long, val elapsedMs: Long, val failure: ModuleFailure? = null)

@Singleton
class ModuleDiagnostics @Inject constructor() {
    private val mutableState = MutableStateFlow<Map<String, ModuleDiagnostic>>(emptyMap())
    val state = mutableState.asStateFlow()
    fun record(id: String, diagnostic: ModuleDiagnostic) {
        mutableState.update { it + (id to diagnostic) }
    }
}

class ModuleTrackMetadataRepository @Inject constructor(private val database: MusicDatabase) {
    suspend fun get(id: String): ModuleMatchMetadata? = withContext(Dispatchers.IO) {
        database.withTransaction {
            getSongByIdBlocking(id)?.let { item ->
                ModuleMatchMetadata(item.title, item.artists.map { it.name }, item.song.albumName, item.song.duration,
                    item.song.explicit, !item.song.isLocal && !item.song.isPodcast && !item.song.isMusicVideo)
            }
        }
    }
}

@Singleton
class ResolveModuleAudioUseCase @Inject constructor(
    private val repository: ModuleRepository,
    private val metadataRepository: ModuleTrackMetadataRepository,
    private val diagnostics: ModuleDiagnostics,
) {
    private val mutex = Mutex()
    private data class Entry(val moduleRevision: String, val metadata: ModuleMatchMetadata, val selection: ModuleAudioSelection?, val expiresAtMs: Long)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    fun enabled(): Boolean = repository.state.value?.let { it.enabled && it.modules.any { module -> module.enabled } } == true

    fun peek(id: String, metadata: ModuleMatchMetadata?): ModuleAudioSelection? {
        val registry = repository.state.value ?: return null
        if (!registry.enabled) return null
        for (module in registry.modules.filter { it.enabled }) {
            val entry = cache[cacheId(id, module.id)]?.takeIf {
                it.moduleRevision == module.revision && (metadata == null || it.metadata == metadata) && it.expiresAtMs > System.currentTimeMillis()
            } ?: return null
            if (entry.selection != null) return entry.selection
        }
        return null
    }

    suspend fun resolve(id: String, metadata: ModuleMatchMetadata? = null): ModuleAudioSelection? {
        val registry = try { repository.snapshot() }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            diagnostics.record("registry", ModuleDiagnostic(ModuleCheckStatus.FAILED, System.currentTimeMillis(), 0, ModuleFailure.INVALID_CONFIGURATION))
            return null
        }
        if (!registry.enabled || registry.modules.none { it.enabled } || id.isLocalMediaId()) return null
        val target = metadata ?: metadataRepository.get(id) ?: return null
        if (!target.eligible || target.title.isBlank() || target.artists.none(String::isNotBlank)) return null
        return withTimeoutOrNull(30_000) {
            mutex.withLock {
                try {
                for (module in registry.modules.filter { it.enabled }) {
                    if (!stillCurrent(registry, module)) return@withTimeoutOrNull null
                    val key = cacheId(id, module.id)
                    val cached = cache[key]?.takeIf { it.moduleRevision == module.revision && it.metadata == target && it.expiresAtMs > System.currentTimeMillis() }
                    if (cached != null) {
                        if (cached.selection != null) return@withTimeoutOrNull cached.selection
                        continue
                    }
                    val started = System.currentTimeMillis()
                    try {
                        var completed = false
                        val candidate = withTimeoutOrNull(15_000) {
                            ModuleRepository.validateConfiguration(module.manifest, module.configuration)
                            val tracks = repository.runtime.search(engineId(module), module.source, module.origin,
                                "${target.title} ${target.artists.joinToString(" ")}", module.configuration)
                            val match = tracks.mapNotNull { track -> score(target, track)?.let { track to it } }
                                .maxByOrNull { it.second }?.first
                            val result = if (match == null) {
                                diagnostics.record(module.id, ModuleDiagnostic(ModuleCheckStatus.NO_MATCH, System.currentTimeMillis(), System.currentTimeMillis() - started))
                                null
                            } else selection(id, module, match.id)
                            completed = true
                            result
                        }
                        if (!completed) diagnostics.record(module.id, ModuleDiagnostic(ModuleCheckStatus.TIMEOUT, System.currentTimeMillis(), System.currentTimeMillis() - started, ModuleFailure.TIMEOUT))
                        if (!stillCurrent(registry, module)) return@withTimeoutOrNull null
                        if (candidate != null) {
                            cache[key] = Entry(module.revision, target, candidate, candidate.stream.expiresAtMs - 60_000)
                            diagnostics.record(module.id, ModuleDiagnostic(ModuleCheckStatus.RESOLVED, System.currentTimeMillis(), System.currentTimeMillis() - started))
                            return@withTimeoutOrNull candidate
                        }
                        if (completed) cache[key] = Entry(module.revision, target, null, System.currentTimeMillis() + 30_000)
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) {
                        val failure = (error as? ModuleException)?.failure ?: ModuleFailure.EXECUTION
                        diagnostics.record(module.id, ModuleDiagnostic(when (failure) {
                            ModuleFailure.INVALID_CONFIGURATION -> ModuleCheckStatus.CONFIGURATION
                            ModuleFailure.NETWORK -> ModuleCheckStatus.UNAVAILABLE
                            ModuleFailure.TIMEOUT -> ModuleCheckStatus.TIMEOUT
                            else -> ModuleCheckStatus.FAILED
                        },
                            System.currentTimeMillis(), System.currentTimeMillis() - started, failure))
                        cache[key] = Entry(module.revision, target, null, System.currentTimeMillis() + 10_000)
                    }
                }
                    null
                } finally {
                    while (cache.size > 256) cache.keys.firstOrNull()?.let(cache::remove)
                }
            }
        }
    }

    suspend fun refresh(selection: ModuleAudioSelection): ModuleAudioSelection {
        if (selection.stream.expiresAtMs > System.currentTimeMillis() + 60_000) return selection
        val refreshed = this.selection(selection.originalId, selection.module, selection.trackId)
        if (refreshed.cacheKey != selection.cacheKey ||
            (selection.stream.contentLength > 0 && refreshed.stream.contentLength > 0 && selection.stream.contentLength != refreshed.stream.contentLength)) {
            throw ModuleException(ModuleFailure.EXECUTION)
        }
        return refreshed
    }

    private suspend fun selection(id: String, module: InstalledModule, trackId: String): ModuleAudioSelection {
        val stream = repository.runtime.stream(engineId(module), module.source, module.origin, trackId, module.configuration)
        val identity = listOf(id, module.id, module.revision, trackId, stream.variant, stream.mimeType, stream.codecs,
            stream.bitrate.toString(), stream.sampleRate?.toString().orEmpty()).joinToString("") { "${it.length}:$it" }
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        return ModuleAudioSelection(id, module, trackId, stream, "module:$digest")
    }

    private fun score(target: ModuleMatchMetadata, track: ModuleTrack): Double? {
        if (track.title.isBlank() || track.artists.isEmpty()) return null
        if (track.explicit != null && target.explicit != track.explicit) return null
        val targetTitle = FuzzyMatcher.normalize(target.title)
        val candidateTitle = FuzzyMatcher.normalize(track.title)
        val markers = listOf("live", "remix", "cover", "karaoke", "instrumental", "acoustic", "sped up", "slowed", "remaster", "remastered", "radio edit", "extended", "demo", "clean", "mono", "stereo")
        if (markers.any { marker -> Regex("(?:^| )${Regex.escape(marker)}(?: |$)").containsMatchIn(targetTitle) !=
                Regex("(?:^| )${Regex.escape(marker)}(?: |$)").containsMatchIn(candidateTitle) }) return null
        val titleScore = FuzzyMatcher.jaroWinkler(targetTitle, candidateTitle)
        if (titleScore != 1.0 && markers.any { Regex("(?:^| )${Regex.escape(it)}(?: |$)").containsMatchIn(targetTitle) }) return null
        val artistScore = target.artists.maxOf { artist -> track.artists.maxOf { FuzzyMatcher.jaroWinkler(artist, it) } }
        if (titleScore < 0.9 || artistScore < 0.9) return null
        if (target.duration > 0 && track.durationSeconds != null) {
            if (kotlin.math.abs(target.duration - track.durationSeconds) > maxOf(5.0, target.duration * 0.05)) return null
        } else if (titleScore != 1.0 || artistScore != 1.0) return null
        val albumScore = if (!target.album.isNullOrBlank() && !track.album.isNullOrBlank()) FuzzyMatcher.jaroWinkler(target.album, track.album) else 0.0
        return titleScore * 0.6 + artistScore * 0.35 + albumScore * 0.05
    }

    private fun engineId(module: InstalledModule) = "${module.id}:${module.revision}"
    private fun cacheId(id: String, moduleId: String) = "${id.length}:$id$moduleId"

    private fun stillCurrent(expected: ModuleRegistry, module: InstalledModule): Boolean {
        val current = repository.state.value ?: return false
        if (!current.enabled) return false
        val preceding = expected.modules.filter { it.enabled }.takeWhile { it.id != module.id } + module
        return current.modules.filter { it.enabled }.take(preceding.size).map { it.id to it.revision } == preceding.map { it.id to it.revision }
    }
}
