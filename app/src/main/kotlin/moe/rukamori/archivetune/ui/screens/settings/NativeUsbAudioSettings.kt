package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.domain.player.AudioBypassMode
import moe.rukamori.archivetune.playback.NativeUsbAudioStatus
import moe.rukamori.archivetune.ui.component.ListPreference
import moe.rukamori.archivetune.ui.component.LocalMenuState
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.PreferenceGroup
import moe.rukamori.archivetune.ui.player.AudioSignalPanel
import moe.rukamori.archivetune.viewmodels.AudioSignalViewModel
import moe.rukamori.archivetune.viewmodels.NativeUsbAudioSettingsUiState
import moe.rukamori.archivetune.viewmodels.NativeUsbAudioSettingsViewModel
import moe.rukamori.archivetune.viewmodels.UsbAvailabilityUiState

@Composable
fun NativeUsbAudioSettings(
    viewModel: NativeUsbAudioSettingsViewModel = hiltViewModel(),
    signalViewModel: AudioSignalViewModel = hiltViewModel(key = "audio-settings-signal"),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val usbAvailability by viewModel.usbAvailability.collectAsStateWithLifecycle()
    val signal by signalViewModel.state.collectAsStateWithLifecycle()
    val connection = LocalPlayerConnection.current
    val menu = LocalMenuState.current

    DisposableEffect(connection, signalViewModel) {
        connection?.let(signalViewModel::bind)
        onDispose { connection?.let(signalViewModel::unbind) }
    }

    val model = signal.model
    val status = when {
        state is NativeUsbAudioSettingsUiState.Loading -> stringResource(R.string.loading)
        state is NativeUsbAudioSettingsUiState.Error -> stringResource(R.string.audio_settings_save_failed)
        usbAvailability == UsbAvailabilityUiState.Error -> stringResource(R.string.usb_availability_unavailable)
        state.mode == AudioBypassMode.Usb && model?.usbBackgroundProtectionFailed == true -> stringResource(R.string.usb_background_playback_unavailable)
        state.mode == AudioBypassMode.Usb && model?.usbEnabled == true -> stringResource(model.usbMessageRes)
        else -> nativeUsbAudioStatusText(model?.nativeStatus, state.mode != AudioBypassMode.Off)
    }
    NativeUsbAudioSettingsContent(state, status, usbAvailability == UsbAvailabilityUiState.Available, viewModel::setMode, onShowSignal = { menu.show { AudioSignalPanel() } })
}

@Composable
private fun NativeUsbAudioSettingsContent(
    state: NativeUsbAudioSettingsUiState,
    status: String,
    usbAvailable: Boolean,
    onModeSelected: (AudioBypassMode) -> Unit,
    onShowSignal: () -> Unit,
) {
    PreferenceGroup(title = stringResource(R.string.audio_output)) {
        item {
            ListPreference(
                title = { Text(stringResource(R.string.audio_bypass)) },
                icon = { Icon(painterResource(R.drawable.graphic_eq), null) },
                selectedValue = state.mode ?: AudioBypassMode.Off,
                values = AudioBypassMode.entries,
                isValueEnabled = { it != AudioBypassMode.Usb || usbAvailable },
                valueText = { mode ->
                    stringResource(
                        when (mode) {
                            AudioBypassMode.Off -> R.string.audio_bypass_off
                            AudioBypassMode.Native -> R.string.audio_bypass_native
                            AudioBypassMode.Usb -> R.string.audio_bypass_usb
                        },
                    )
                },
                isEnabled = state !is NativeUsbAudioSettingsUiState.Loading && state.mode != null,
                onValueSelected = onModeSelected,
            )
        }
        item {
            PreferenceEntry(
                title = { Text(stringResource(R.string.audio_status)) },
                description = status,
                icon = { Icon(painterResource(R.drawable.info), null) },
                onClick = onShowSignal,
            )
        }
    }
}

@Composable
internal fun nativeUsbAudioStatusText(status: NativeUsbAudioStatus?, enabled: Boolean): String = stringResource(
    when {
        status == null -> R.string.usb_audio_service_unavailable

        !enabled -> R.string.usb_audio_disabled

        else -> when (status) {
            NativeUsbAudioStatus.Disabled,
            NativeUsbAudioStatus.WaitingForPlayback,
            -> R.string.usb_audio_waiting

            NativeUsbAudioStatus.AndroidUnsupported -> R.string.usb_audio_android_unsupported

            NativeUsbAudioStatus.UsbDisconnected -> R.string.usb_audio_disconnected

            NativeUsbAudioStatus.MixerUnsupported -> R.string.usb_audio_mixer_unsupported

            NativeUsbAudioStatus.FormatUnsupported -> R.string.usb_audio_format_unsupported

            NativeUsbAudioStatus.RequestRejected -> R.string.usb_audio_request_rejected

            NativeUsbAudioStatus.RequestAccepted -> R.string.usb_audio_request_accepted
        }
    },
)
