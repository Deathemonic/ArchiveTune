package moe.rukamori.archivetune.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NativeUsbAudioStatus {
    Disabled,
    WaitingForPlayback,
    AndroidUnsupported,
    UsbDisconnected,
    MixerUnsupported,
    FormatUnsupported,
    RequestRejected,
    RequestAccepted,
}

data class NativeUsbAudioState(
    val status: NativeUsbAudioStatus = NativeUsbAudioStatus.Disabled,
    val deviceName: String? = null,
    val sourceFormat: Format? = null,
    val outputFormat: AudioFormat? = null,
    val supportedFormats: List<AudioFormat> = emptyList(),
)

@OptIn(UnstableApi::class)
class NativeUsbAudioController(private val context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mutableState = MutableStateFlow(NativeUsbAudioState())
    val state = mutableState.asStateFlow()

    @Volatile
    var enabled = false
        private set

    private var generation = 0L
    private val mixer = if (Build.VERSION.SDK_INT >= 34) Mixer() else null

    @Synchronized
    fun updateEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        generation++

        if (Build.VERSION.SDK_INT >= 34) mixer?.clear()
        mutableState.value = NativeUsbAudioState(sourceFormat = mutableState.value.sourceFormat)
        refresh()
    }

    @Synchronized
    fun updateSource(format: Format?) {
        mutableState.value = mutableState.value.copy(sourceFormat = format)
    }

    @Synchronized
    fun refresh() {
        if (!enabled) {
            mutableState.value = mutableState.value.copy(status = NativeUsbAudioStatus.Disabled)

            return
        }

        if (Build.VERSION.SDK_INT < 34) {
            mutableState.value =
                mutableState.value.copy(status = NativeUsbAudioStatus.AndroidUnsupported)

            return
        }
        mixer?.refresh()
    }

    @Synchronized
    fun release() {
        generation++

        if (Build.VERSION.SDK_INT >= 34) mixer?.release()
        mutableState.value = NativeUsbAudioState()
    }

    fun createAudioOutputProvider(
        delegate: AudioOutputProvider = AudioTrackAudioOutputProvider.Builder(context).build(),
    ): AudioOutputProvider = object : ForwardingAudioOutputProvider(delegate) {
        override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
            val (lease, bypassProcessing) = synchronized(this@NativeUsbAudioController) {
                prepare(config) to enabled
            }

            val output =
                try {
                    super.getAudioOutput(config)
                } catch (error: Exception) {
                    releaseOutput(lease, failed = true)
                    throw error
                }

            if (Build.VERSION.SDK_INT >= 34) {
                synchronized(this@NativeUsbAudioController) {
                    if (lease == generation) mixer?.device?.let(output::setPreferredDevice)
                }
            }

            return object : AudioOutput by output {
                override fun setVolume(volume: Float) {
                    output.setVolume(if (bypassProcessing) 1f else volume)
                }

                override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
                    output.setPlaybackParameters(
                        if (bypassProcessing) PlaybackParameters.DEFAULT else playbackParameters,
                    )
                }

                override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) {
                    val usbDevice = synchronized(this@NativeUsbAudioController) {
                        if (bypassProcessing && enabled && lease == generation &&
                            Build.VERSION.SDK_INT >= 34
                        ) {
                            mixer?.device
                        } else {
                            null
                        }
                    }
                    output.setPreferredDevice(usbDevice ?: preferredDevice)
                }

                override fun release() {
                    try {
                        output.release()
                    } finally {
                        releaseOutput(lease)
                    }
                }
            }
        }
    }

    @Synchronized
    private fun prepare(config: AudioOutputProvider.OutputConfig): Long {
        generation++

        if (Build.VERSION.SDK_INT >= 34) mixer?.clear()
        val format =
            AudioFormat.Builder()
                .setEncoding(config.encoding)
                .setSampleRate(config.sampleRate)
                .setChannelMask(config.channelMask)
                .build()
        mutableState.value = mutableState.value.copy(outputFormat = format)

        if (enabled && Build.VERSION.SDK_INT >= 34) {
            mixer?.configure(config, format)
        } else {
            refresh()
        }

        return generation
    }

    @Synchronized
    private fun releaseOutput(lease: Long, failed: Boolean = false) {
        if (lease != generation) return

        if (Build.VERSION.SDK_INT >= 34) mixer?.clear()
        mutableState.value = mutableState.value.copy(outputFormat = null)
        refresh()

        if (enabled && failed) {
            mutableState.value =
                mutableState.value.copy(status = NativeUsbAudioStatus.RequestRejected)
        }
    }

    @RequiresApi(34)
    private inner class Mixer {
        var device: AudioDeviceInfo? = null
            private set
        private var attributes: AudioAttributes? = null
        private var requestedMixer: AudioMixerAttributes? = null
        private var listenerRegistered = false
        private val listener =
            AudioManager.OnPreferredMixerAttributesChangedListener { _, changedDevice, _ ->
                synchronized(this@NativeUsbAudioController) {
                    if (device?.id == changedDevice.id && enabled) refresh()
                }
            }

        fun refresh() {
            try {
                val usb = findUsbDevice()

                if (device != null && device?.id != usb?.id) clear()

                if (usb == null) {
                    clear()
                    mutableState.value = mutableState.value.copy(
                        status = NativeUsbAudioStatus.UsbDisconnected,
                        deviceName = null,
                        supportedFormats = emptyList(),
                    )

                    return
                }

                val formats = supportedMixers(usb).map { it.format }
                val requested = requestedMixer
                val playbackAttributes = attributes
                val accepted =
                    requested != null && playbackAttributes != null &&
                        audioManager.getPreferredMixerAttributes(playbackAttributes, usb) ==
                        requested
                mutableState.value = mutableState.value.copy(
                    status = when {
                        accepted -> NativeUsbAudioStatus.RequestAccepted
                        requested != null -> NativeUsbAudioStatus.RequestRejected
                        formats.isEmpty() -> NativeUsbAudioStatus.MixerUnsupported
                        mutableState.value.status == NativeUsbAudioStatus.RequestRejected -> NativeUsbAudioStatus.RequestRejected
                        mutableState.value.outputFormat != null -> NativeUsbAudioStatus.FormatUnsupported
                        else -> NativeUsbAudioStatus.WaitingForPlayback
                    },
                    deviceName = usb.productName?.toString(),
                    supportedFormats = formats,
                )
            } catch (_: RuntimeException) {
                mutableState.value =
                    mutableState.value.copy(status = NativeUsbAudioStatus.RequestRejected)
            }
        }

        fun configure(config: AudioOutputProvider.OutputConfig, format: AudioFormat) {
            refresh()

            try {
                val usb = findUsbDevice() ?: return
                val mixers = supportedMixers(usb)

                if (mixers.isEmpty()) return
                val matching = mixers.firstOrNull { it.format == format }

                if (matching == null) {
                    mutableState.value =
                        mutableState.value.copy(status = NativeUsbAudioStatus.FormatUnsupported)

                    return
                }

                val playbackAttributes = config.audioAttributes.platformAudioAttributes

                if (!listenerRegistered) {
                    audioManager.addOnPreferredMixerAttributesChangedListener(
                        context.mainExecutor,
                        listener,
                    )
                    listenerRegistered = true
                }

                if (audioManager.setPreferredMixerAttributes(playbackAttributes, usb, matching)) {
                    device = usb
                    attributes = playbackAttributes
                    requestedMixer = matching
                    refresh()
                } else {
                    mutableState.value =
                        mutableState.value.copy(status = NativeUsbAudioStatus.RequestRejected)
                }
            } catch (_: RuntimeException) {
                clear()
                mutableState.value =
                    mutableState.value.copy(status = NativeUsbAudioStatus.RequestRejected)
            }
        }

        fun clear() {
            val previousDevice = device
            val previousAttributes = attributes
            device = null
            attributes = null
            requestedMixer = null

            if (previousDevice != null && previousAttributes != null) {
                runCatching {
                    audioManager.clearPreferredMixerAttributes(previousAttributes, previousDevice)
                }
            }
        }

        fun release() {
            clear()

            if (listenerRegistered) {
                runCatching {
                    audioManager.removeOnPreferredMixerAttributesChangedListener(listener)
                }
                listenerRegistered = false
            }
        }

        private fun supportedMixers(device: AudioDeviceInfo): List<AudioMixerAttributes> = audioManager.getSupportedMixerAttributes(device)
            .filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }

        private fun findUsbDevice(): AudioDeviceInfo? = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.isSink &&
                (
                    it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                    )
        }
    }
}
