package moe.rukamori.archivetune.playback

import dagger.hilt.android.scopes.ViewModelScoped
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import moe.rukamori.archivetune.domain.player.UsbPlaybackProtectionState
import moe.rukamori.archivetune.models.ActiveOutputDevice

data class AudioSignalData(
    val source: StreamingSourceData,
    val native: NativeUsbAudioState,
    val usb: DirectUsbAudioState,
    val outputDevice: ActiveOutputDevice,
    val backgroundProtection: UsbPlaybackProtectionState = UsbPlaybackProtectionState.Disabled,
)

@OptIn(ExperimentalCoroutinesApi::class)
@ViewModelScoped
class AudioSignalRepository @Inject constructor(private val streamingSources: StreamingSourceRepository) {
    private val connection = MutableStateFlow<PlayerConnection?>(null)
    private var attachments = 0

    fun attach(playerConnection: PlayerConnection) {
        attachments = if (connection.value === playerConnection) attachments + 1 else 1
        streamingSources.attach(playerConnection)
        connection.value = playerConnection
        playerConnection.service.refreshActiveDevice()
    }

    fun detach(playerConnection: PlayerConnection) {
        if (connection.value !== playerConnection) return
        attachments--
        streamingSources.detach(playerConnection)

        if (attachments == 0) connection.value = null
    }

    fun observeSignal(): Flow<AudioSignalData?> = connection.flatMapLatest { playerConnection ->
        if (playerConnection == null) {
            flowOf(null)
        } else {
            val service = playerConnection.service
            combine(
                streamingSources.observeSource(),
                service.nativeUsbAudioState,
                service.directUsbAudioState,
                service.activeAudioDevice,
                service.usbPlaybackProtectionState,
            ) { source, native, usb, outputDevice, protection ->
                source?.let { AudioSignalData(it, native, usb, outputDevice, protection) }
            }
        }
    }

    fun requestUsbPermission() {
        connection.value?.service?.requestDirectUsbPermission()
    }
}
