package moe.rukamori.archivetune.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import androidx.annotation.WorkerThread
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.domain.player.UsbPlaybackProtectionFailure
import moe.rukamori.archivetune.domain.player.UsbPlaybackProtectionState
import timber.log.Timber

internal class UsbPlaybackKeepAlive(scope: CoroutineScope, private val createTrack: () -> Track) {
    constructor(context: Context, scope: CoroutineScope) : this(scope, { SpeakerTrack(context) })

    internal interface Track {
        fun play()
        fun pause()
        fun release()
    }

    private sealed interface Command {
        val result: CompletableFuture<UsbPlaybackProtectionState>
        data class Prepare(val enabled: Boolean, override val result: CompletableFuture<UsbPlaybackProtectionState>) : Command
        data class Playback(val playing: Boolean, override val result: CompletableFuture<UsbPlaybackProtectionState>) : Command
    }

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val closed = AtomicBoolean()
    private val mutableState = MutableStateFlow<UsbPlaybackProtectionState>(UsbPlaybackProtectionState.Disabled)
    val state = mutableState.asStateFlow()
    val isPlaying get() = state.value == UsbPlaybackProtectionState.Playing
    private var track: Track? = null
    private var playing = false
    @Volatile private var preparation = CompletableFuture.completedFuture<UsbPlaybackProtectionState>(UsbPlaybackProtectionState.Disabled)
    private var requestedPlaying: Boolean? = null
    private var playbackResult = preparation

    private val worker = scope.launch(Dispatchers.IO) {
        try {
            for (command in commands) {
                if (closed.get()) {
                    command.result.cancel(false)
                    continue
                }
                try {
                    val result = when (command) {
                        is Command.Prepare -> prepareTrack(command.enabled)
                        is Command.Playback -> updatePlayback(command.playing)
                    }
                    currentCoroutineContext().ensureActive()
                    if (closed.get()) {
                        command.result.cancel(false)
                    } else {
                        mutableState.value = result
                        command.result.complete(result)
                    }
                } catch (cancellation: CancellationException) {
                    command.result.cancel(false)
                    throw cancellation
                }
            }
        } finally {
            closed.set(true)
            commands.close()
            withContext(NonCancellable + Dispatchers.IO) {
                mutableState.value = releaseTrack()
            }
        }
    }.also { job ->
        job.invokeOnCompletion {
            closed.set(true)
            commands.close()
            while (true) {
                val pending = commands.tryReceive().getOrNull() ?: break
                pending.result.cancel(false)
            }
        }
    }

    @Synchronized
    fun prepare(enabled: Boolean): CompletableFuture<UsbPlaybackProtectionState> {
        val result = CompletableFuture<UsbPlaybackProtectionState>()
        preparation = result
        requestedPlaying = null
        playbackResult = CompletableFuture.completedFuture(mutableState.value)
        if (closed.get() || commands.trySend(Command.Prepare(enabled, result)).isFailure) result.cancel(false)
        return result
    }

    @Synchronized
    fun setPlaying(playing: Boolean): CompletableFuture<UsbPlaybackProtectionState> {
        if (closed.get()) return CompletableFuture<UsbPlaybackProtectionState>().also { it.cancel(false) }
        if (requestedPlaying == playing) return playbackResult
        requestedPlaying = playing
        val result = CompletableFuture<UsbPlaybackProtectionState>()
        playbackResult = result
        if (closed.get() || commands.trySend(Command.Playback(playing, result)).isFailure) result.cancel(false)
        return result
    }

    @WorkerThread
    fun awaitPreparedOnPlaybackThread() {
        while (true) {
            val pending = preparation
            pending.get()
            if (closed.get() || !worker.isActive) throw CancellationException("USB playback protection is closed")
            if (pending === preparation) return
        }
    }

    fun close() = worker.also {
        closed.set(true)
        commands.close()
    }

    private fun prepareTrack(enabled: Boolean): UsbPlaybackProtectionState {
        if (!enabled) return releaseTrack()
        if (track != null) return if (playing) UsbPlaybackProtectionState.Playing else UsbPlaybackProtectionState.Ready
        mutableState.value = UsbPlaybackProtectionState.Preparing
        return try {
            track = createTrack()
            UsbPlaybackProtectionState.Ready
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            failed(UsbPlaybackProtectionFailure.Setup, failure)
        }
    }

    private fun updatePlayback(requested: Boolean): UsbPlaybackProtectionState {
        val current = track ?: return mutableState.value
        if (playing == requested) return mutableState.value
        return try {
            if (requested) current.play() else current.pause()
            playing = requested
            if (playing) UsbPlaybackProtectionState.Playing else UsbPlaybackProtectionState.Ready
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            val error = failed(UsbPlaybackProtectionFailure.Playback, failure)
            val cleanup = releaseTrack()
            if (cleanup is UsbPlaybackProtectionState.Error) cleanup else error
        }
    }

    private fun releaseTrack(): UsbPlaybackProtectionState {
        val current = track
        track = null
        playing = false
        return try {
            current?.release()
            UsbPlaybackProtectionState.Disabled
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            failed(UsbPlaybackProtectionFailure.Release, failure)
        }
    }

    private fun failed(operation: UsbPlaybackProtectionFailure, failure: Exception): UsbPlaybackProtectionState.Error {
        Timber.tag("UsbPlayback").w(failure, "USB background playback protection failed: %s", operation)
        return UsbPlaybackProtectionState.Error(operation)
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
