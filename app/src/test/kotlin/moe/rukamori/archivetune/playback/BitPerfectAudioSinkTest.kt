package moe.rukamori.archivetune.playback

import android.app.Application
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class BitPerfectAudioSinkTest {
    private lateinit var normal: RecordingAudioSink
    private lateinit var native: RecordingAudioSink
    private lateinit var sink: BitPerfectAudioSink
    private var enabled = false

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        normal = RecordingAudioSink(context, highResolution = false)
        native = RecordingAudioSink(context, highResolution = true)
        sink = BitPerfectAudioSink(normal, native) { enabled }
    }

    @After
    fun tearDown() {
        sink.release()
    }

    @Test
    fun decoderNegotiation_usesHighResolutionOnlyWhenRequested() {
        val format = format(C.ENCODING_PCM_FLOAT)
        assertNotEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(format))

        enabled = true

        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(format))
    }

    @Test
    fun outputSwitching_preservesBuffersUntilReconfiguration_andRestoresNormalOutput() {
        sink.configure(format(C.ENCODING_PCM_16BIT), 0, null)
        enabled = true
        sink.handleBuffer(ByteBuffer.allocate(8), 0, 1)
        assertEquals(1, normal.buffers)
        assertEquals(0, native.buffers)

        sink.configure(format(C.ENCODING_PCM_FLOAT), 0, null)
        sink.handleBuffer(ByteBuffer.allocate(8), 0, 1)

        assertEquals(1, normal.resets)
        assertEquals(1, native.buffers)
        assertEquals(C.ENCODING_PCM_FLOAT, native.configuredFormat?.pcmEncoding)

        enabled = false
        sink.configure(format(C.ENCODING_PCM_16BIT), 0, null)
        sink.handleBuffer(ByteBuffer.allocate(8), 0, 1)

        assertEquals(1, native.resets)
        assertEquals(2, normal.buffers)
        assertEquals(C.ENCODING_PCM_16BIT, normal.configuredFormat?.pcmEncoding)
    }

    @Test
    fun processingControls_remainAvailableOnNormalSink_andBypassedOnNativeSink() {
        sink.setSkipSilenceEnabled(true)
        sink.setPlaybackParameters(PlaybackParameters(1.5f, 1.2f))
        sink.setVolume(0.3f)

        assertEquals(true, normal.skipSilence)
        assertEquals(false, native.skipSilence)
        assertEquals(PlaybackParameters(1.5f, 1.2f), normal.parameters)
        assertEquals(PlaybackParameters.DEFAULT, native.parameters)
        assertEquals(0.3f, normal.lastVolume, 0f)
        assertEquals(1f, native.lastVolume, 0f)
    }

    private fun format(encoding: Int): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setPcmEncoding(encoding)
        .setChannelCount(2)
        .setSampleRate(44_100)
        .build()

    private class RecordingAudioSink(context: Context, highResolution: Boolean) : ForwardingAudioSink(DefaultAudioSink.Builder(context).setEnableFloatOutput(highResolution).build()) {
        var buffers = 0
        var resets = 0
        var configuration: AudioSink.AudioSinkConfig? = null
        val configuredFormat: Format? get() = configuration?.format
        var skipSilence = false
        var parameters = PlaybackParameters.DEFAULT
        var lastVolume = 0f

        override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
            configuration = audioSinkConfig
        }

        override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
            buffers++

            return true
        }

        override fun reset() {
            resets++
        }

        override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
            skipSilence = skipSilenceEnabled
        }

        override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
            parameters = playbackParameters
        }

        override fun setVolume(value: Float) {
            lastVolume = value
        }
    }
}
