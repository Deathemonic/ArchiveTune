package moe.rukamori.archivetune.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import timber.log.Timber

internal class UsbPlaybackKeepAlive(private val createTrack: () -> Track) {
    constructor(context: Context) : this({ SpeakerTrack(context) })

    internal interface Track {
        fun play()
        fun pause()
        fun release()
    }

    private var track: Track? = null
    var isPlaying = false
        private set

    fun prepare(enabled: Boolean) {
        if (!enabled) {
            release()
            return
        }
        if (track != null) return
        try {
            track = createTrack()
        } catch (failure: Exception) {
            Timber.tag("UsbPlayback").w(failure, "Unable to prepare USB background keepalive")
        }
    }

    fun setPlaying(playing: Boolean) {
        if (playing == isPlaying) return
        val current = track ?: return
        try {
            if (playing) current.play() else current.pause()
            isPlaying = playing
        } catch (failure: Exception) {
            Timber.tag("UsbPlayback").w(failure, "USB background keepalive state change failed")
            release()
        }
    }

    fun release() {
        val current = track ?: return
        track = null
        isPlaying = false
        runCatching { current.release() }
            .onFailure { Timber.tag("UsbPlayback").w(it, "USB background keepalive release failed") }
    }

    private class SpeakerTrack(context: Context) : Track {
        private val output: AudioTrack

        init {
            val manager = context.getSystemService(AudioManager::class.java)
            val speaker = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            checkNotNull(speaker) { "No built-in speaker is available for USB background keepalive" }
            val frames = 4_800
            val silence = ByteArray(frames * 2)
            val created = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(48_000)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(silence.size)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            try {
                check(created.setPreferredDevice(speaker)) { "Unable to route USB background keepalive to speaker" }
                check(created.write(silence, 0, silence.size) == silence.size) { "Unable to load keepalive silence" }
                check(created.setLoopPoints(0, frames, -1) == AudioTrack.SUCCESS) { "Unable to loop keepalive silence" }
            } catch (failure: Exception) {
                created.release()
                throw failure
            }
            output = created
        }

        override fun play() = output.play()
        override fun pause() = output.pause()
        override fun release() = output.release()
    }
}
