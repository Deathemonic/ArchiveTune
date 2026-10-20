package moe.rukamori.archivetune.ui.player

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import java.util.Locale
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.playback.StreamingSourceData
import moe.rukamori.archivetune.sources.SourceFailure
import moe.rukamori.archivetune.sources.SourcePlaybackStatus
import moe.rukamori.archivetune.sources.SourceProvider
import moe.rukamori.archivetune.utils.isLocalMediaId

enum class StreamingSource(
    @param:StringRes val labelRes: Int,
    @param:DrawableRes val iconRes: Int,
) {
    Local(R.string.audio_source_local, R.drawable.music_note),
    Qobuz(R.string.qobuz, R.drawable.album),
    Deezer(R.string.deezer, R.drawable.deezer),
    Tidal(R.string.tidal, R.drawable.tidal),
    Resolving(R.string.audio_source_resolving, R.drawable.graphic_eq),
    Unavailable(R.string.audio_source_unavailable, R.drawable.graphic_eq),
    Stream(R.string.audio_source_stream, R.drawable.graphic_eq),
    YouTube(R.string.audio_source_youtube, R.drawable.youtube_music),
    Unknown(R.string.unknown, R.drawable.graphic_eq),
}

@Immutable
data class StreamingSourceUiModel(
    val title: String?,
    val fallbackProvider: SourceProvider?,
    val fallbackFailure: SourceFailure?,
    val source: StreamingSource,
    val bitDepth: Int?,
    val sampleRate: Int?,
    val codec: String?,
)

@Composable
internal fun formatSampleRate(rate: Int): String = if (rate % 1000 == 0) {
    stringResource(R.string.audio_sample_rate_integer, rate / 1000)
} else {
    stringResource(R.string.eq_frequency_kilohertz, rate / 1000.0)
}

internal fun StreamingSourceData.toStreamingSourceUiModel(): StreamingSourceUiModel {
    val local = metadata?.id?.isLocalMediaId() == true
    val selected = sourcePlayback?.takeIf { externalSourcesEnabled && !local }
    val selectedSource = selected?.takeIf { it.status == SourcePlaybackStatus.Selected }
    val source = when {
        metadata == null -> StreamingSource.Unknown

        local -> StreamingSource.Local

        selected?.status == SourcePlaybackStatus.Selected -> when (selectedSource?.provider) {
            SourceProvider.Deezer -> StreamingSource.Deezer
            SourceProvider.Tidal -> StreamingSource.Tidal
            SourceProvider.Qobuz -> StreamingSource.Qobuz
            null -> StreamingSource.Unknown
        }

        selected?.status == SourcePlaybackStatus.Resolving -> StreamingSource.Resolving

        selected?.status == SourcePlaybackStatus.Unavailable -> StreamingSource.Unavailable

        selected?.status == SourcePlaybackStatus.YouTube -> StreamingSource.YouTube

        metadata.id.toUri().scheme in setOf("http", "https") -> StreamingSource.Stream

        else -> StreamingSource.YouTube
    }

    val fallback = selected?.takeIf {
        it.status == SourcePlaybackStatus.YouTube || it.status == SourcePlaybackStatus.Unavailable
    }?.failures?.entries?.firstOrNull()

    return StreamingSourceUiModel(
        title = metadata?.title,
        fallbackProvider = fallback?.key,
        fallbackFailure = fallback?.value,
        source = source,
        bitDepth = selectedSource?.bitDepth,
        sampleRate = selectedSource?.sampleRate ?: sampleRate,
        codec = (selectedSource?.mimeType ?: mimeType)?.substringAfter('/')?.uppercase(Locale.ROOT),
    )
}
