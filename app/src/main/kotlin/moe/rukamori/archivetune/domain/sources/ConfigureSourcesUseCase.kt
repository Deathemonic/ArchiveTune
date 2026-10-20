package moe.rukamori.archivetune.domain.sources

import javax.inject.Inject
import moe.rukamori.archivetune.sources.SourceProvider
import moe.rukamori.archivetune.sources.SourceRepository

class ConfigureSourcesUseCase @Inject constructor(private val repository: SourceRepository) {
    val settings = repository.settings

    suspend fun setPriority(priority: List<SourceProvider>) = repository.setPriority(priority.distinct())
    suspend fun setYouTubeFallback(allowed: Boolean) = repository.setYouTubeFallback(allowed)
}
