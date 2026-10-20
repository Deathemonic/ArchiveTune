package moe.rukamori.archivetune.sources.qobuz

import androidx.compose.runtime.Immutable
import java.util.Locale
import moe.rukamori.archivetune.sources.SourceSongQuery
import moe.rukamori.archivetune.sources.SourceTrack

class QobuzCredentials(val appId: String, val appSecret: String, val userToken: String) {
    override fun toString(): String = "QobuzCredentials(redacted)"
}

data class QobuzSettings(
    val connected: Boolean = false,
    val legacyYouTubeFallback: Boolean = true,
    val quality: QobuzQuality = QobuzQuality.Master,
    val matchRevision: Long = 0L,
    val accountRevision: Long = 0L,
)

enum class QobuzQuality(val formats: List<Int>) {
    Standard(listOf(5)),
    Cd(listOf(6)),
    Studio(listOf(7, 6)),
    Master(listOf(27, 7, 6)),
}

typealias QobuzTrack = SourceTrack

typealias QobuzSongQuery = SourceSongQuery

data class QobuzStream(
    val track: QobuzTrack,
    val url: String,
    val formatId: Int,
    val sampleRate: Double,
    val bitDepth: Int,
) {
    override fun toString(): String = "QobuzStream(redacted)"
}

enum class QobuzPlaybackStatus {
    Resolving,
    Lossless,
    Lossy,
    NoMatch,
    Unavailable,
    AccountError,
}

data class QobuzPlaybackState(
    val status: QobuzPlaybackStatus,
    val track: QobuzTrack? = null,
    val sampleRate: Double? = null,
    val bitDepth: Int? = null,
    val failure: QobuzFailure? = null,
)

enum class QobuzFailureReason {
    Storage,
    AppId,
    Token,
    Signature,
    Subscription,
    Region,
    Clock,
    RateLimit,
    Preview,
    Format,
    Transport,
    Rejected,
    Network,
}

enum class QobuzRequestStage {
    Account,
    Search,
    Stream,
}

@Immutable
data class QobuzFailure(
    val reason: QobuzFailureReason,
    val stage: QobuzRequestStage? = null,
    val httpStatus: Int? = null,
)

internal fun qobuzFailureReason(status: Int, message: String): QobuzFailureReason {
    val text = message.lowercase(Locale.ROOT)

    return when {
        "signature" in text || "request_sig" in text -> QobuzFailureReason.Signature
        "app_id" in text || "app id" in text || "application id" in text -> QobuzFailureReason.AppId
        "request_ts" in text || "timestamp" in text -> QobuzFailureReason.Clock
        "token" in text -> QobuzFailureReason.Token
        "subscription" in text || "credential" in text || "not eligible" in text -> QobuzFailureReason.Subscription
        "country" in text || "territor" in text || "region" in text || "geograph" in text -> QobuzFailureReason.Region
        status == 429 -> QobuzFailureReason.RateLimit
        status >= 500 -> QobuzFailureReason.Network
        else -> QobuzFailureReason.Rejected
    }
}
