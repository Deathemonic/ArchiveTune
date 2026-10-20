package moe.rukamori.archivetune.playback

import android.media.AudioDeviceInfo
import androidx.annotation.RequiresApi
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer

@OptIn(UnstableApi::class)
class BitPerfectAudioSink(
    private val normalSink: AudioSink,
    private val nativeSink: AudioSink,
    private val bitPerfectEnabled: () -> Boolean,
) : AudioSink {
    private val sinks = listOf(normalSink, nativeSink)
    private val requestedSink get() = if (bitPerfectEnabled()) nativeSink else normalSink
    private var activeSink = requestedSink

    override fun setListener(listener: AudioSink.Listener) = sinks.forEach { it.setListener(listener) }

    override fun setPlayerId(playerId: PlayerId?) = sinks.forEach { it.setPlayerId(playerId) }

    override fun setClock(clock: Clock) = sinks.forEach { it.setClock(clock) }

    override fun supportsFormat(format: Format): Boolean = requestedSink.supportsFormat(format)

    override fun getFormatSupport(format: Format): Int = requestedSink.getFormatSupport(format)

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport = if (bitPerfectEnabled()) AudioOffloadSupport.DEFAULT_UNSUPPORTED else normalSink.getFormatOffloadSupport(format)

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long = activeSink.getCurrentPositionUs(sourceEnded)

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        val selected = requestedSink

        if (activeSink !== selected) {
            activeSink.reset()
            activeSink = selected
        }
        activeSink.configure(audioSinkConfig)
    }

    override fun play() = sinks.forEach { it.play() }

    override fun handleDiscontinuity() = activeSink.handleDiscontinuity()

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean = activeSink.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)

    override fun playToEndOfStream() = activeSink.playToEndOfStream()

    override fun isEnded(): Boolean = activeSink.isEnded

    override fun hasPendingData(): Boolean = activeSink.hasPendingData()

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        normalSink.setPlaybackParameters(playbackParameters)
        nativeSink.setPlaybackParameters(PlaybackParameters.DEFAULT)
    }

    override fun getPlaybackParameters(): PlaybackParameters = activeSink.playbackParameters

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        normalSink.setSkipSilenceEnabled(skipSilenceEnabled)
        nativeSink.setSkipSilenceEnabled(false)
    }

    override fun getSkipSilenceEnabled(): Boolean = activeSink.skipSilenceEnabled

    override fun setAudioAttributes(audioAttributes: AudioAttributes) = sinks.forEach { it.setAudioAttributes(audioAttributes) }

    override fun getAudioAttributes(): AudioAttributes? = activeSink.audioAttributes

    override fun getAudioCapabilities(): AudioCapabilities? = activeSink.audioCapabilities

    override fun setAudioSessionId(audioSessionId: Int) = sinks.forEach { it.setAudioSessionId(audioSessionId) }

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {
        normalSink.setAuxEffectInfo(auxEffectInfo)
        nativeSink.setAuxEffectInfo(AuxEffectInfo(0, 0f))
    }

    override fun setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?) = sinks.forEach { it.setPreferredDevice(audioDeviceInfo) }

    override fun setVirtualDeviceId(virtualDeviceId: Int) = sinks.forEach { it.setVirtualDeviceId(virtualDeviceId) }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) = sinks.forEach { it.setOutputStreamOffsetUs(outputStreamOffsetUs) }

    override fun getAudioTrackBufferSizeUs(): Long = activeSink.audioTrackBufferSizeUs

    override fun enableTunnelingV21() = normalSink.enableTunnelingV21()

    override fun disableTunneling() = sinks.forEach { it.disableTunneling() }

    @RequiresApi(29)
    override fun setOffloadMode(offloadMode: Int) {
        normalSink.setOffloadMode(offloadMode)
        nativeSink.setOffloadMode(AudioSink.OFFLOAD_MODE_DISABLED)
    }

    @RequiresApi(29)
    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) = activeSink.setOffloadDelayPadding(delayInFrames, paddingInFrames)

    override fun setAudioOutputProvider(audioOutputProvider: AudioOutputProvider) = activeSink.setAudioOutputProvider(audioOutputProvider)

    override fun setVolume(volume: Float) {
        normalSink.setVolume(volume)
        nativeSink.setVolume(1f)
    }

    override fun pause() = sinks.forEach { it.pause() }

    override fun flush() = sinks.forEach { it.flush() }

    override fun reset() = sinks.forEach { it.reset() }

    override fun release() = sinks.forEach { it.release() }
}
