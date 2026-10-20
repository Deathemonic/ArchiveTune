package moe.rukamori.archivetune.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import moe.rukamori.archivetune.lyrics.LyricsPlaybackClock

@Composable
internal fun rememberLyricsPlaybackPosition(
    player: Player,
    sessionKey: Any?,
    sliderPositionProvider: () -> Long?,
    animationsDisabled: Boolean,
    enabled: Boolean = true,
): LongState {
    val position = remember(player) { mutableLongStateOf(player.currentPosition.coerceAtLeast(0L)) }
    val latestSliderPositionProvider = rememberUpdatedState(sliderPositionProvider)
    var discontinuity by remember(player) { mutableIntStateOf(0) }
    var speed by remember(player) { mutableFloatStateOf(player.playbackParameters.speed) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason != Player.DISCONTINUITY_REASON_INTERNAL) discontinuity++
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                speed = playbackParameters.speed
            }
        }

        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(player, sessionKey, enabled, animationsDisabled, speed, discontinuity) {
        val clock = LyricsPlaybackClock()
        position.longValue = clock.reset(player.currentPosition)

        if (!enabled) return@LaunchedEffect

        while (isActive) {
            val sliderPosition = latestSliderPositionProvider.value()
            val rawPosition = (sliderPosition ?: player.currentPosition).coerceAtLeast(0L)

            if (sliderPosition != null || !player.isPlaying || animationsDisabled) {
                position.longValue = if (sliderPosition != null || animationsDisabled) clock.reset(rawPosition) else clock.pause()

                if (sliderPosition == null) delay(100L) else withFrameNanos { }
            } else {
                val frameNanos = withFrameNanos { it }
                position.longValue = clock.update(player.currentPosition, frameNanos, speed)
            }
        }
    }

    return position
}
