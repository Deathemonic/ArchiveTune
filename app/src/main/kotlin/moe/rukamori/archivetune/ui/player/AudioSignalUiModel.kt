package moe.rukamori.archivetune.ui.player

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.media3.common.C
import androidx.media3.common.Format
import java.util.Locale
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.domain.player.UsbPlaybackProtectionState
import moe.rukamori.archivetune.playback.AudioSignalData
import moe.rukamori.archivetune.playback.NativeUsbAudioStatus
import moe.rukamori.archivetune.sources.SourceFailure
import moe.rukamori.archivetune.sources.SourceProvider

@Immutable
data class AudioSignalUiModel(
    val title: String?,
    val nativeStatus: NativeUsbAudioStatus,
    val outputEncoding: Int?,
    val outputSampleRate: Int?,
    val usbEnabled: Boolean,
    @param:StringRes val usbMessageRes: Int,
    val usbPermissionRequired: Boolean,
    val usbBackgroundProtectionFailed: Boolean,
    val usbStream: UsbStreamUiModel?,
    val underruns: Long,
    val transferErrors: Long,
    val deviceName: String?,
    val deviceIcon: ImageVector,
    val source: StreamingSource,
    val fallbackProvider: SourceProvider?,
    val fallbackFailure: SourceFailure?,
    val bitDepth: Int?,
    val sampleRate: Int?,
    val codec: String?,
    val channelCount: Int?,
    val isLossless: Boolean,
    val isProcessingBypassed: Boolean,
)

@Immutable
data class UsbStreamUiModel(
    val bitsPerSample: Int,
    val outputRate: Int,
    val channels: Int,
    val deviceRate: Int,
    val bitResolution: Int,
    val containerBits: Int,
)

internal fun sourcePcmBitDepth(format: Format?): Int? {
    if (format?.sampleMimeType !in
        setOf("audio/raw", "audio/flac", "audio/x-flac", "audio/alac")
    ) {
        return null
    }

    return when (format?.pcmEncoding) {
        C.ENCODING_PCM_8BIT -> 8
        C.ENCODING_PCM_16BIT -> 16
        C.ENCODING_PCM_24BIT -> 24
        C.ENCODING_PCM_32BIT -> 32
        else -> null
    }
}

@Composable
internal fun formatSampleRate(rate: Int): String = if (rate % 1000 == 0) {
    stringResource(R.string.audio_sample_rate_integer, rate / 1000)
} else {
    stringResource(R.string.eq_frequency_kilohertz, rate / 1000.0)
}

internal fun AudioSignalData.toAudioSignalUiModel(): AudioSignalUiModel {
    val stream = source.toStreamingSourceUiModel()
    val format = native.sourceFormat

    return AudioSignalUiModel(
        title = stream.title,
        nativeStatus = native.status,
        outputEncoding = native.outputFormat?.encoding,
        outputSampleRate = native.outputFormat?.sampleRate,
        usbEnabled = usb.enabled,
        usbMessageRes = usb.messageRes,
        usbPermissionRequired = usb.permissionRequired,
        usbBackgroundProtectionFailed = usb.enabled && backgroundProtection is UsbPlaybackProtectionState.Error,
        usbStream = usb.stream?.let {
            UsbStreamUiModel(it.bitsPerSample, it.outputRate, it.channels, it.deviceRate, it.format.bitResolution, it.format.subslotBytes * 8)
        },
        underruns = usb.underruns,
        transferErrors = usb.errors,
        deviceName = when {
            usb.enabled -> usb.deviceName
            native.status == NativeUsbAudioStatus.RequestAccepted -> native.deviceName ?: outputDevice.name
            else -> outputDevice.name
        },
        deviceIcon = if (usb.enabled) Icons.Rounded.Usb else outputDevice.type.imageVector,
        source = stream.source,
        fallbackProvider = stream.fallbackProvider,
        fallbackFailure = stream.fallbackFailure,
        bitDepth = sourcePcmBitDepth(format) ?: stream.bitDepth,
        sampleRate = format?.sampleRate?.takeIf { it > 0 } ?: stream.sampleRate,
        codec = format?.sampleMimeType?.substringAfter('/')?.uppercase(Locale.ROOT) ?: stream.codec,
        channelCount = format?.channelCount?.takeIf { it > 0 },
        isLossless = source.mimeType in setOf("audio/flac", "audio/x-flac", "audio/alac", "audio/raw") ||
            format?.sampleMimeType in setOf("audio/flac", "audio/x-flac", "audio/alac", "audio/raw"),
        isProcessingBypassed = usb.enabled || native.status != NativeUsbAudioStatus.Disabled,
    )
}
