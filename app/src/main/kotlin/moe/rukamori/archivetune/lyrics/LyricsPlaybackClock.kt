package moe.rukamori.archivetune.lyrics

import kotlin.math.exp
import kotlin.math.roundToLong

internal class LyricsPlaybackClock {
    private var positionMs = 0.0
    private var previousFrameNanos = 0L

    fun reset(rawPositionMs: Long): Long {
        positionMs = rawPositionMs.coerceAtLeast(0L).toDouble()
        previousFrameNanos = 0L

        return positionMs.roundToLong()
    }

    fun update(rawPositionMs: Long, frameNanos: Long, speed: Float): Long {
        val rawPosition = rawPositionMs.coerceAtLeast(0L).toDouble()

        if (previousFrameNanos == 0L) {
            previousFrameNanos = frameNanos
            return positionMs.roundToLong()
        }

        val elapsedMs = (frameNanos - previousFrameNanos).coerceAtLeast(0L) / 1_000_000.0
        val elapsedMediaMs = elapsedMs * speed
        val projectedPosition = positionMs + elapsedMediaMs
        val driftMs = rawPosition - projectedPosition
        val correction = driftMs * (1.0 - exp(-elapsedMs / CORRECTION_TIME_MS))
        val maxCorrection = elapsedMediaMs * MAX_RATE_CORRECTION

        positionMs = projectedPosition + correction.coerceIn(-maxCorrection, maxCorrection)
        previousFrameNanos = frameNanos

        return positionMs.roundToLong()
    }

    fun pause(): Long {
        previousFrameNanos = 0L

        return positionMs.roundToLong()
    }

    private companion object {
        const val CORRECTION_TIME_MS = 500.0
        const val MAX_RATE_CORRECTION = 0.1
    }
}
