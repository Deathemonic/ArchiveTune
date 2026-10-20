package moe.rukamori.archivetune.playback

import com.truesample.usbaudio.PlaybackPlan
import com.truesample.usbaudio.StreamInfo
import com.truesample.usbaudio.StreamStats
import com.truesample.usbaudio.UsbAudioOutput
import com.truesample.usbaudio.VolumeRange
import java.nio.ByteBuffer

internal interface UsbTransport {
    val volumeRange: VolumeRange?
    fun plan(rate: Int, bits: Int, channels: Int): PlaybackPlan?
    fun start(rate: Int, bits: Int, channels: Int): StreamInfo
    fun write(buffer: ByteBuffer, frames: Int): Int
    fun pause(paused: Boolean)
    fun drain()
    fun stop()
    fun close()
    fun stats(): StreamStats
    fun setVolumeDb(db: Float): Boolean
}

internal class NativeUsbTransport(private val output: UsbAudioOutput) : UsbTransport {
    override val volumeRange get() = output.volumeRange
    override fun plan(rate: Int, bits: Int, channels: Int) = output.plan(rate, bits, channels)
    override fun start(rate: Int, bits: Int, channels: Int) = output.start(rate, bits, channels)
    override fun write(buffer: ByteBuffer, frames: Int) = output.write(buffer, frames)
    override fun pause(paused: Boolean) = output.pause(paused)
    override fun drain() = output.drain()
    override fun stop() = output.stop()
    override fun close() = output.close()
    override fun stats() = output.stats()
    override fun setVolumeDb(db: Float) = output.setVolumeDb(db)
}
