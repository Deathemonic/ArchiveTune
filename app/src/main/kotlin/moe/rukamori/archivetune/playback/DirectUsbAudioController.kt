package moe.rukamori.archivetune.playback

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioFormat
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import com.truesample.usbaudio.StreamInfo
import com.truesample.usbaudio.UsbAudioOutput
import com.truesample.usbaudio.VolumeRange
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.R
import timber.log.Timber

data class DirectUsbAudioState(
    val enabled: Boolean = false,
    @param:StringRes val messageRes: Int = R.string.usb_status_off,
    val deviceName: String? = null,
    val stream: StreamInfo? = null,
    val volume: VolumeRange? = null,
    val underruns: Long = 0,
    val errors: Long = 0,
    val volumeError: Boolean = false,
    val permissionRequired: Boolean = false,
)

@OptIn(FlowPreview::class)
class DirectUsbAudioController(
    private val context: Context,
    private val onReady: () -> Unit = {},
    private val beforeOpen: () -> Unit = {},
    private val onUnavailable: () -> Unit,
) {
    private val manager = context.getSystemService(UsbManager::class.java)
    private val volumes = UsbVolumeStore(context.getSharedPreferences("usb_hardware_volume", Context.MODE_PRIVATE))
    private val permissionAction = "${context.packageName}.USB_AUDIO_PERMISSION"
    private val mutableState = MutableStateFlow(DirectUsbAudioState())
    val state = mutableState.asStateFlow()

    @Volatile var enabled = false
        private set

    @Volatile private var active: DirectUsbAudioOutput? = null

    @Volatile private var permissionDeviceId: Int? = null
    private val volumeScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO,
    )
    private data class VolumeRequest(val output: DirectUsbAudioOutput, val volumeDb: Float, val revision: Long)

    private val volumeRevision = AtomicLong()
    private val volumeRequests = MutableStateFlow<VolumeRequest?>(null)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)

            when (intent.action) {
                permissionAction -> {
                    if (device == null || device.deviceId != permissionDeviceId) return

                    permissionDeviceId = null

                    if (!enabled || findDevice()?.deviceId != device.deviceId) return

                    val granted = manager.hasPermission(device)

                    mutableState.update { it.copy(permissionRequired = !granted) }
                    report(if (granted) R.string.usb_status_ready else R.string.usb_status_denied)

                    if (granted) onReady()
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (device?.deviceId == permissionDeviceId) permissionDeviceId = null

                    if (!enabled) return

                    if (device?.deviceId == active?.deviceId) {
                        active?.let(::disconnected)
                    } else if (findDevice() == null) {
                        enabled = false
                        mutableState.update {
                            it.copy(enabled = false, permissionRequired = false, deviceName = null, messageRes = R.string.usb_status_off)
                        }
                        onUnavailable()
                    }
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> if (enabled) requestPermission()
            }
        }
    }

    init {
        volumeScope.launch {
            volumeRequests.filterNotNull().debounce(60).collect { request ->
                val (output, volumeDb) = request

                try {
                    if (active === output) {
                        output.setHardwareVolume(volumeDb)
                        val volume = output.volumeRange

                        if (active === output && volume != null) {
                            volumes.confirm(output.volumeKey, volumeDb, volume.currentDb)
                            mutableState.update {
                                if (active === output) {
                                    it.copy(volume = volume, volumeError = false)
                                } else {
                                    it
                                }
                            }
                        }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    Timber.w("USB hardware volume update failed (%s)", failure.javaClass.simpleName)
                    volumes.reject(output.volumeKey, volumeDb)
                    volumeRequests.compareAndSet(request, null)
                    mutableState.update {
                        if (active === output) {
                            it.copy(volumeError = true)
                        } else {
                            it
                        }
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter().apply {
                addAction(permissionAction)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun updateEnabled(value: Boolean) {
        enabled = value

        if (!value) permissionDeviceId = null
        mutableState.update {
            it.copy(
                enabled = value,
                permissionRequired = false,
                messageRes = if (value) R.string.usb_status_connect else R.string.usb_status_off,
            )
        }

        if (value) requestPermission()
    }

    @Synchronized
    fun requestPermission() {
        if (!enabled) return

        val device = findDevice() ?: run {
            permissionDeviceId = null
            mutableState.update { it.copy(permissionRequired = false, deviceName = null) }
            updateEnabled(false)
            onUnavailable()

            return
        }
        val granted = manager.hasPermission(device)

        mutableState.update {
            it.copy(permissionRequired = !granted, deviceName = device.productName ?: device.deviceName)
        }

        if (granted) {
            permissionDeviceId = null

            if (active == null) report(R.string.usb_status_ready)
        } else if (permissionDeviceId != device.deviceId) {
            permissionDeviceId = device.deviceId
            report(R.string.usb_status_permission)

            try {
                manager.requestPermission(
                    device,
                    PendingIntent.getBroadcast(
                        context,
                        device.deviceId,
                        Intent(permissionAction).setPackage(context.packageName),
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
            } catch (_: RuntimeException) {
                permissionDeviceId = null
                report(R.string.usb_status_request_failed)
            }
        }
    }

    private fun ready(): Boolean {
        if (!enabled) return false

        val device = findDevice() ?: return false

        return manager.hasPermission(device)
    }

    internal fun hasAttachedDevice(): Boolean = findDevice() != null

    private fun findDevice(): UsbDevice? = manager.deviceList.values
        .filter { UsbAudioOutput.isUsbAudioOutput(it) }.sortedBy { it.deviceId }.firstOrNull()

    @Synchronized
    internal fun disconnected(output: DirectUsbAudioOutput) {
        if (active !== output || !enabled) return
        enabled = false
        output.disconnect()
        mutableState.update {
            it.copy(enabled = false, stream = null, volume = null, permissionRequired = false, deviceName = null, messageRes = R.string.usb_status_off)
        }
        onUnavailable()
    }

    internal fun report(@StringRes messageRes: Int) = mutableState.update {
        it.copy(messageRes = messageRes, stream = null)
    }

    internal fun update(output: DirectUsbAudioOutput, info: StreamInfo) {
        active = output
        mutableState.update {
            it.copy(
                messageRes = R.string.usb_status_direct,
                permissionRequired = false,
                deviceName = output.deviceName,
                stream = info,
                volume = output.volumeRange,
                underruns = 0,
                errors = 0,
                volumeError = false,
            )
        }
    }

    internal fun updateStats(underruns: Long, errors: Long) {
        mutableState.update {
            if (it.underruns == underruns && it.errors == errors) it else it.copy(underruns = underruns, errors = errors)
        }
    }

    internal fun released(output: DirectUsbAudioOutput) {
        if (active === output) {
            active = null
            mutableState.update {
                it.copy(
                    stream = null,
                    volume = null,
                    messageRes = if (output.failed) {
                        it.messageRes
                    } else if (enabled) {
                        R.string.usb_status_ready
                    } else {
                        R.string.usb_status_off
                    },
                )
            }
        }
    }

    internal fun restoreVolume(output: DirectUsbAudioOutput) {
        val range = output.volumeRange ?: throw UsbAudioException(R.string.usb_error_volume_unavailable)
        val pending = volumeRequests.value?.takeIf { it.output.volumeKey == output.volumeKey }
        val saved = pending?.volumeDb ?: volumes.read(output.volumeKey)
        checkUsb(saved != null || range.minDb <= -30f, R.string.usb_error_quiet_volume)
        output.setHardwareVolume((saved ?: -30f).coerceIn(range.minDb, range.maxDb))
        output.volumeRange?.let { volume ->
            volumes.confirm(output.volumeKey, saved ?: -30f, volume.currentDb)
        }

        if (pending != null) volumeRequests.compareAndSet(pending, null)
    }

    fun setVolume(volumeDb: Float) {
        if (!volumeDb.isFinite()) return
        val output = active ?: return
        val range = mutableState.value.volume ?: return
        val selected = volumeDb.coerceIn(range.minDb, range.maxDb)
        volumes.select(output.volumeKey, selected)
        volumeRequests.value = VolumeRequest(output, selected, volumeRevision.incrementAndGet())
    }

    fun adjustVolume(direction: Int): Boolean {
        if (!enabled || direction == 0) return false
        val output = active ?: return false
        val range = mutableState.value.volume ?: return false

        if (range.maxDb <= range.minDb) return false
        val current =
            volumeRequests.value?.takeIf { it.output === output }?.volumeDb ?: range.currentDb
        setVolume(current + maxOf(1f, range.stepDb) * direction.coerceIn(-1, 1))

        return true
    }

    fun provider(delegate: AudioOutputProvider, sourceBits: () -> Int?): AudioOutputProvider = object : ForwardingAudioOutputProvider(delegate) {
        override fun getFormatSupport(
            config: AudioOutputProvider.FormatConfig,
        ): AudioOutputProvider.FormatSupport {
            if (!ready()) return super.getFormatSupport(config)
            val format = config.format

            return if (format.sampleMimeType == MimeTypes.AUDIO_RAW &&
                format.pcmEncoding in setOf(
                    C.ENCODING_PCM_16BIT,
                    C.ENCODING_PCM_24BIT,
                    C.ENCODING_PCM_32BIT,
                    C.ENCODING_PCM_FLOAT,
                )
            ) {
                AudioOutputProvider.FormatSupport.Builder().setFormatSupportLevel(
                    AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY,
                ).build()
            } else {
                AudioOutputProvider.FormatSupport.UNSUPPORTED
            }
        }

        override fun getOutputConfig(
            config: AudioOutputProvider.FormatConfig,
        ): AudioOutputProvider.OutputConfig {
            if (!ready()) return super.getOutputConfig(config)
            val format = config.format

            if (getFormatSupport(
                    config,
                ).supportLevel == AudioOutputProvider.FORMAT_UNSUPPORTED ||
                format.channelCount !in 1..2 ||
                format.sampleRate <= 0
            ) {
                throw AudioOutputProvider.ConfigurationException(
                    context.getString(R.string.usb_error_pcm_format),
                )
            }

            return AudioOutputProvider.OutputConfig.Builder().setEncoding(
                format.pcmEncoding,
            ).setSampleRate(format.sampleRate)
                .setChannelMask(
                    if (format.channelCount == 1) {
                        AudioFormat.CHANNEL_OUT_MONO
                    } else {
                        AudioFormat.CHANNEL_OUT_STEREO
                    },
                )
                .setBufferSize(
                    format.sampleRate * format.channelCount * UsbPcm.bytesPerSample(format.pcmEncoding),
                )
                .setAudioAttributes(config.audioAttributes).build()
        }

        override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
            if (!enabled) return super.getAudioOutput(config)
            var openingDeviceId: Int? = null

            try {
                val device = findDevice() ?: throw UsbAudioException(R.string.usb_status_connect)
                openingDeviceId = device.deviceId
                if (!manager.hasPermission(device)) {
                    requestPermission()

                    return super.getAudioOutput(config)
                }
                beforeOpen()
                if (!enabled) return super.getAudioOutput(config)
                checkUsb(active == null, R.string.usb_error_stream_closing)

                return DirectUsbAudioOutput(
                    device.deviceId,
                    device.productName ?: device.deviceName,
                    config,
                    sourceBits(),
                    this@DirectUsbAudioController,
                    NativeUsbTransport(UsbAudioOutput.open(manager, device)),
                    volumeKey = listOf(
                        device.vendorId,
                        device.productId,
                        device.version,
                        runCatching {
                            device.serialNumber
                        }.getOrNull().orEmpty(),
                    ).joinToString(":"),
                )
            } catch (e: Exception) {
                if (manager.deviceList.values.none { it.deviceId == openingDeviceId }) {
                    enabled = false
                    mutableState.update {
                        it.copy(
                            enabled = false,
                            stream = null,
                            volume = null,
                            messageRes = R.string.usb_status_off,
                        )
                    }
                    onUnavailable()

                    return super.getAudioOutput(config)
                }
                val messageRes = (e as? UsbAudioException)?.messageRes ?: R.string.usb_error_open
                report(messageRes)
                Timber.w("USB output initialization failed (%s)", e.javaClass.simpleName)
                throw AudioOutputProvider.InitializationException(IllegalStateException(context.getString(messageRes)))
            }
        }
    }

    fun release() {
        volumeScope.cancel()
        context.unregisterReceiver(receiver)
    }
}
