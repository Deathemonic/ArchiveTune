package moe.rukamori.archivetune.playback

import android.media.AudioDeviceInfo
import android.os.Process
import androidx.annotation.StringRes
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import moe.rukamori.archivetune.R

internal class DirectUsbAudioOutput(
    val deviceId: Int,
    val deviceName: String,
    private val config: AudioOutputProvider.OutputConfig,
    sourceBits: Int?,
    private val controller: DirectUsbAudioController,
    private val driver: UsbTransport,
    val volumeKey: String = deviceName,
) : AudioOutput {
    private val channels = Integer.bitCount(config.channelMask)
    private val sampleBytes = UsbPcm.bytesPerSample(config.encoding)
    private val bits = if (config.encoding == C.ENCODING_PCM_FLOAT) sourceBits ?: 24 else sampleBytes * 8
    val volumeRange get() = driver.volumeRange
    private val listeners = CopyOnWriteArraySet<AudioOutput.Listener>()
    private val queue = ArrayBlockingQueue<ByteBuffer>(16)
    private val chunkFrames = config.sampleRate / 100
    private val buffers = ArrayBlockingQueue<ByteBuffer>(18)
    private val writtenFrames = AtomicLong()
    private var lastPosition = 0L

    @Volatile private var closing = false

    @Volatile private var playing = false

    @Volatile private var ending = false

    @Volatile private var failure = false

    @Volatile private var drained = false

    @Volatile private var detached = false
    private val worker: Thread

    init {
        try {
            checkUsb(config.encoding != C.ENCODING_PCM_FLOAT || bits <= 24, R.string.usb_error_integer_decoder)
            checkUsb(sourceBits == null || config.encoding == C.ENCODING_PCM_FLOAT || sourceBits <= sampleBytes * 8, R.string.usb_error_decoder_precision)
            controller.restoreVolume(this)
            val plan = driver.plan(config.sampleRate, bits, channels)
            checkUsb(plan != null && plan.bitPerfect && !plan.resampled && plan.outputRate == config.sampleRate, R.string.usb_error_unsupported_format)

            val stream = driver.start(config.sampleRate, bits, channels)
            checkUsb(stream.bitPerfect && !stream.resampled && stream.outputRate == config.sampleRate, R.string.usb_error_format_changed)
            checkUsb(stream.deviceRate == 0 || stream.deviceRate == config.sampleRate, R.string.usb_error_rate_mismatch)
            controller.update(this, stream)
        } catch (e: Exception) {
            driver.close()
            throw e
        }
        repeat(18) {
            buffers.add(ByteBuffer.allocateDirect(chunkFrames * channels * 4).order(ByteOrder.LITTLE_ENDIAN))
        }
        worker = thread(name = "ArchiveTune-USB", isDaemon = true) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

            try {
                while (!closing) {
                    if (!playing) {
                        Thread.sleep(10)
                        continue
                    }

                    val buffer = queue.poll(10, TimeUnit.MILLISECONDS)

                    if (buffer != null) {
                        val frames = buffer.remaining() / (channels * 4)
                        try {
                            checkUsb(driver.write(buffer, frames) == frames, R.string.usb_error_write)
                        } finally {
                            buffers.offer(buffer)
                        }
                    } else if (ending) {
                        driver.drain()
                        drained = true
                        break
                    }
                }
            } catch (_: InterruptedException) {
                if (!closing) fail(R.string.usb_error_interrupted)
            } catch (_: Exception) {
                if (!closing) {
                    if (runCatching { driver.stats().disconnected }.getOrDefault(false)) {
                        controller.disconnected(this)
                    } else {
                        fail(R.string.usb_error_transfer)
                    }
                }
            }
        }
    }

    internal val failed get() = failure

    private fun fail(@StringRes messageRes: Int) {
        failure = true
        controller.report(messageRes)
    }

    fun disconnect() {
        detached = true
        playing = false
        driver.stop()
    }

    fun setHardwareVolume(db: Float) {
        val range = volumeRange ?: throw UsbAudioException(R.string.usb_error_volume_unavailable)
        val target = db.coerceIn(range.minDb, range.maxDb)
        checkUsb(driver.setVolumeDb(target), R.string.usb_error_volume_set)
        val confirmed = driver.volumeRange?.currentDb
        checkUsb(confirmed != null && confirmed.isFinite() && kotlin.math.abs(confirmed - target) <= maxOf(1f, range.stepDb), R.string.usb_error_volume_unconfirmed)
    }

    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        if (detached) {
            buffer.position(buffer.limit())

            return true
        }

        if (failure || closing || ending) throw AudioOutput.WriteException(-1, false)

        while (buffer.hasRemaining()) {
            if (queue.remainingCapacity() == 0) return false

            val output = buffers.poll() ?: return false
            val bytes = minOf(buffer.remaining(), chunkFrames * channels * sampleBytes)
            val converted = try {
                UsbPcm.convert(buffer, config.encoding, bytes, output)
            } catch (_: Exception) {
                fail(R.string.usb_error_samples)
                throw AudioOutput.WriteException(-2, false)
            }

            if (!queue.offer(converted)) {
                buffers.offer(converted)
                return false
            }

            buffer.position(buffer.position() + bytes)
            writtenFrames.addAndGet((bytes / (channels * sampleBytes)).toLong())
        }

        return true
    }

    override fun play() {
        if (closing || failure || detached) return
        driver.pause(false)
        playing = true
        listeners.forEach { it.onPositionAdvancing(System.currentTimeMillis()) }
    }

    override fun pause() {
        if (closing) return
        playing = false
        driver.pause(true)
    }

    override fun stop() {
        ending = true
    }

    override fun flush() {
        release()
    }

    override fun release() {
        if (closing) return
        closing = true
        driver.stop()
        worker.join()
        driver.close()
        queue.clear()
        buffers.clear()
        controller.released(this)
        listeners.forEach { it.onReleased() }
        listeners.clear()
    }

    override fun getPositionUs(): Long {
        if (closing || detached) return lastPosition
        val stats = driver.stats()
        controller.updateStats(stats.underruns, stats.transferErrors)

        if (stats.disconnected) {
            controller.disconnected(this)

            return lastPosition
        }

        if (stats.transferErrors > 0) fail(R.string.usb_error_transfer)
        val frames = if (drained) writtenFrames.get() else minOf(stats.framesSent, writtenFrames.get())
        lastPosition = maxOf(lastPosition, frames * 1_000_000L / config.sampleRate)

        return lastPosition
    }

    override fun canReuseAudioOutput(currentConfig: AudioOutputProvider.OutputConfig, newFormat: AudioOutputProvider.FormatConfig, newConfig: AudioOutputProvider.OutputConfig): Boolean = false

    override fun getBufferSizeInFrames(): Long = config.sampleRate.toLong()
    override fun getSampleRate(): Int = config.sampleRate
    override fun getAudioSessionId(): Int = C.AUDIO_SESSION_ID_UNSET
    override fun isOffloadedPlayback(): Boolean = false
    override fun isStalled(): Boolean = failure
    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT
    override fun setPlaybackParameters(playbackParams: PlaybackParameters) = Unit
    override fun setVolume(volume: Float) = Unit
    override fun addListener(listener: AudioOutput.Listener) {
        listeners.add(listener)
    }
    override fun removeListener(listener: AudioOutput.Listener) {
        listeners.remove(listener)
    }
    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) = Unit
    override fun setOffloadEndOfStream() = Unit
    override fun attachAuxEffect(effectId: Int) = Unit
    override fun setAuxEffectSendLevel(level: Float) = Unit
    override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) = Unit
}
