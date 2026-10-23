package moe.rukamori.archivetune.domain.player

import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import moe.rukamori.archivetune.modules.ModulePlaybackSource
import moe.rukamori.archivetune.modules.ModulePlaybackSourceRepository
import moe.rukamori.archivetune.modules.ModuleRepository

sealed interface ModulePlaybackIndicator {
    data object Hidden : ModulePlaybackIndicator
    data object YouTube : ModulePlaybackIndicator
    data class Module(val source: ModulePlaybackSource) : ModulePlaybackIndicator
}

class ObserveModulePlaybackSourceUseCase @Inject constructor(
    private val modules: ModuleRepository,
    private val playback: ModulePlaybackSourceRepository,
) {
    operator fun invoke(mediaId: Flow<String?>): Flow<ModulePlaybackIndicator> = combine(
        flow { modules.snapshot(); emitAll(modules.state.filterNotNull()) },
        playback.sources,
        mediaId,
    ) { registry, sources, id ->
        modulePlaybackIndicator(registry.enabled && registry.modules.any { it.enabled }, id, sources)
    }
}

internal fun modulePlaybackIndicator(
    enabled: Boolean,
    mediaId: String?,
    sources: Map<String, ModulePlaybackSource>,
): ModulePlaybackIndicator = when {
    !enabled || mediaId == null -> ModulePlaybackIndicator.Hidden
    sources[mediaId] != null -> ModulePlaybackIndicator.Module(checkNotNull(sources[mediaId]))
    else -> ModulePlaybackIndicator.YouTube
}
