package moe.rukamori.archivetune.sources

data class SourceConfiguration(
    val priority: List<SourceProvider> = SourceProvider.entries.toList(),
    val youtubeFallback: Boolean = true,
)

internal fun sourcePriority(value: String?): List<SourceProvider> {
    val saved = value.orEmpty().split(',').mapNotNull { name ->
        SourceProvider.entries.firstOrNull { it.name == name }
    }.distinct()

    return saved + SourceProvider.entries.filterNot { it in saved }
}
