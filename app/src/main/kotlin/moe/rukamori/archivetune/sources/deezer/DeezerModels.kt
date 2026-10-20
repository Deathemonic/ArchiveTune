package moe.rukamori.archivetune.sources.deezer

import java.io.IOException
import moe.rukamori.archivetune.sources.SourceTrack

class DeezerCredentials(val arl: String) {
    override fun toString(): String = "DeezerCredentials(redacted)"
}

data class DeezerSettings(
    val connected: Boolean = false,
    val legacyYouTubeFallback: Boolean = true,
    val quality: DeezerQuality = DeezerQuality.HiFi,
    val matchRevision: Long = 0L,
    val accountRevision: Long = 0L,
)

enum class DeezerQuality(val format: String, val mimeType: String) {
    Standard("MP3_128", "audio/mpeg"),
    High("MP3_320", "audio/mpeg"),
    HiFi("FLAC", "audio/flac"),
}

typealias DeezerTrack = SourceTrack

internal class DeezerStream(
    val track: DeezerTrack,
    val url: String,
    val quality: DeezerQuality,
) {
    override fun toString(): String = "DeezerStream(redacted)"
}

enum class DeezerFailure {
    Storage,
    Account,
    Subscription,
    Unavailable,
    Network,
}

internal class DeezerException(val reason: DeezerFailure) : IOException("Deezer request failed: $reason")
