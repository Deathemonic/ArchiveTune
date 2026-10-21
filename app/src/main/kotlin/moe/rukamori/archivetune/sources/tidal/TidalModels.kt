package moe.rukamori.archivetune.sources.tidal

import androidx.media3.exoplayer.dash.manifest.DashManifest
import java.io.IOException
import moe.rukamori.archivetune.sources.SourceTrack

class TidalCredentials internal constructor(
    val accessToken: String,
    internal val refreshToken: String? = null,
    internal val oauthClient: TidalOAuthClient? = null,
    internal val expiresAtMs: Long = 0L,
) {
    override fun toString(): String = "TidalCredentials(redacted)"
}

data class TidalSettings(
    val connected: Boolean = false,
    val accountRevision: Long = 0,
    val quality: TidalQuality = TidalQuality.Max,
    val matchRevision: Long = 0L,
)

enum class TidalQuality(val apiValue: String) {
    Low("HIGH"),
    High("LOSSLESS"),
    Max("HI_RES_LOSSLESS"),
}

typealias TidalTrack = SourceTrack

internal class TidalStream(
    val track: TidalTrack,
    val url: String,
    val mimeType: String,
    val quality: String,
    val bitDepth: Int?,
    val sampleRate: Int?,
    val dashManifest: DashManifest? = null,
) {
    override fun toString(): String = "TidalStream(redacted)"
}

enum class TidalFailure {
    Storage,
    Account,
    Subscription,
    Unavailable,
    Network,
    Expired,
    Denied,
}

internal class TidalException(val reason: TidalFailure) : IOException("Tidal request failed: $reason")
