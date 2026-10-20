package moe.rukamori.archivetune.sources

data class SourceTrack(
    val id: Long,
    val title: String,
    val version: String,
    val artist: String,
    val album: String,
    val duration: Int,
    val streamable: Boolean,
    val explicit: Boolean = false,
    val thumbnailUrl: String? = null,
)

data class SourceSongQuery(
    val title: String,
    val artists: List<String>,
    val album: String?,
    val duration: Int,
    val explicit: Boolean = false,
)
