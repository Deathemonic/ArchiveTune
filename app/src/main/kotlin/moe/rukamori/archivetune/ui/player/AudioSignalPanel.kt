package moe.rukamori.archivetune.ui.player

import android.media.AudioFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.ui.screens.settings.nativeUsbAudioStatusText
import moe.rukamori.archivetune.viewmodels.AudioSignalUiState
import moe.rukamori.archivetune.viewmodels.AudioSignalViewModel

@Composable
fun AudioSignalPanel(viewModel: AudioSignalViewModel = hiltViewModel(key = "audio-signal-panel")) {
    val connection = LocalPlayerConnection.current ?: return
    DisposableEffect(connection, viewModel) {
        viewModel.bind(connection)
        onDispose { viewModel.unbind(connection) }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val signal = state) {
        is AudioSignalUiState.Success -> AudioSignalContent(signal.model, viewModel::requestUsbPermission)
        AudioSignalUiState.Loading -> CircularProgressIndicator()
        AudioSignalUiState.Empty -> Text(stringResource(R.string.no_track_playing))
        AudioSignalUiState.Error -> Text(stringResource(R.string.audio_signal_unavailable))
    }
}

@Composable
private fun AudioSignalContent(
    model: AudioSignalUiModel,
    onRequestUsbPermission: () -> Unit,
) {
    val rate = model.sampleRate
    val source = stringResource(model.source.labelRes)
    val sourceFormat = listOfNotNull(
        model.codec,
        model.bitDepth?.let { stringResource(R.string.audio_bit_depth, it) },
        rate?.let { formatSampleRate(it) },
        model.channelCount?.let { stringResource(R.string.audio_channel_count, it) },
    ).joinToString(" · ")
    val bypassed = model.isProcessingBypassed
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().verticalScroll(
            rememberScrollState(),
        ).padding(bottom = 24.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 12.dp),
        ) {
            Icon(
                painterResource(R.drawable.graphic_eq),
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Text(
                stringResource(R.string.audio_signal_details),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        SignalStage(
            model.source.iconRes,
            source,
            listOfNotNull(
                model.title,
                sourceFallbackDescription(model.fallbackProvider, model.fallbackFailure),
                sourceFormat.takeIf {
                    it.isNotBlank()
                },
            ),
            if (model.isLossless) {
                stringResource(
                    R.string.signal_lossless,
                )
            } else {
                stringResource(R.string.signal_lossy)
            },
        )
        SignalConnector()
        SignalStage(
            R.drawable.music_note,
            stringResource(R.string.signal_decoder),
            listOf(
                stringResource(R.string.signal_pcm),
                listOfNotNull(
                    rate?.let { formatSampleRate(it) },
                    model.channelCount?.let {
                        stringResource(R.string.audio_channel_count, it)
                    },
                ).joinToString(" · ").ifBlank { stringResource(R.string.unknown) },
            ),
        )
        SignalConnector()
        SignalStage(
            R.drawable.tune,
            stringResource(R.string.signal_processing),
            listOf(
                stringResource(
                    if (bypassed) R.string.signal_processing_bypassed else R.string.signal_processing_settings,
                ),
            ),
            if (bypassed) stringResource(R.string.signal_bypassed) else null,
        )
        SignalConnector()
        val outputBits = when (model.outputEncoding) {
            AudioFormat.ENCODING_PCM_16BIT -> stringResource(R.string.audio_bit_depth, 16)
            AudioFormat.ENCODING_PCM_FLOAT -> stringResource(R.string.audio_pcm_float32)
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> stringResource(R.string.audio_bit_depth, 24)
            AudioFormat.ENCODING_PCM_32BIT -> stringResource(R.string.audio_bit_depth, 32)
            else -> null
        }
        SignalStage(
            R.drawable.graphic_eq,
            stringResource(
                if (model.usbEnabled) R.string.usb_bypass else R.string.signal_android_output,
            ),
            if (model.usbEnabled) {
                listOfNotNull(
                    stringResource(model.usbMessageRes),
                    model.usbStream?.let {
                        stringResource(R.string.audio_usb_pcm_format, it.bitsPerSample, formatSampleRate(it.outputRate), it.channels)
                    },
                )
            } else {
                listOfNotNull(
                    nativeUsbAudioStatusText(model.nativeStatus, bypassed),
                    model.outputSampleRate?.let {
                        listOfNotNull(
                            outputBits,
                            formatSampleRate(it),
                        ).joinToString(" · ")
                    },
                )
            },
            if (model.usbStream != null) stringResource(R.string.signal_exclusive) else null,
        )
        SignalConnector()
        SignalStage(
            R.drawable.album,
            stringResource(R.string.signal_dac),
            listOfNotNull(
                model.deviceName,
                model.usbStream?.let {
                    stringResource(R.string.audio_dac_format, it.bitResolution, formatSampleRate(it.deviceRate.takeIf { rate -> rate > 0 } ?: it.outputRate))
                },
                model.usbStream?.takeIf { it.containerBits != it.bitResolution }?.let {
                    stringResource(R.string.signal_usb_container, it.containerBits)
                },
                model.usbStream?.let {
                    stringResource(R.string.signal_usb_transfers, model.underruns, model.transferErrors)
                },
            ).ifEmpty {
                listOf(stringResource(R.string.unknown))
            },
            imageVector = model.deviceIcon,
        )

        if (model.usbEnabled && model.usbPermissionRequired) {
            TextButton(onClick = { onRequestUsbPermission() }) {
                Text(stringResource(R.string.usb_grant_access))
            }
        }
    }
}

@Composable
private fun SignalStage(
    icon: Int,
    title: String,
    details: List<String>,
    badge: String? = null,
    imageVector: ImageVector? = null,
) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            if (imageVector != null) {
                Icon(
                    imageVector,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            } else {
                Icon(
                    painterResource(icon),
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.weight(1f),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )

                    if (badge != null) {
                        Text(
                            badge,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                details.forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SignalConnector() {
    val connectorColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.padding(start = 26.dp).size(width = 2.dp, height = 18.dp)) {
        drawLine(
            color = connectorColor,
            start = Offset(size.width / 2, 0f),
            end = Offset(size.width / 2, size.height),
            strokeWidth = size.width,
            cap = StrokeCap.Round,
        )
    }
}
