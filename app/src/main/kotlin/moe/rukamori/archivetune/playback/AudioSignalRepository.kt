package moe.rukamori.archivetune.playback

import dagger.hilt.android.scopes.ViewModelScoped
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import moe.rukamori.archivetune.models.ActiveOutputDevice
import moe.rukamori.archivetune.models.MediaMetadata

data class AudioSignalData(
    val metadata: MediaMetadata?,
    val native: NativeUsbAudioState,
    val usb: DirectUsbAudioState,
    val outputDevice: ActiveOutputDevice,
)

@OptIn(ExperimentalCoroutinesApi::class)
@ViewModelScoped
class AudioSignalRepository @Inject constructor() {
    private val connection = MutableStateFlow<PlayerConnection?>(null)
    private var attachments = 0

    fun attach(playerConnection: PlayerConnection) {
        attachments = if (connection.value === playerConnection) attachments + 1 else 1
        connection.value = playerConnection
        playerConnection.service.refreshActiveDevice()
    }

    fun detach(playerConnection: PlayerConnection) {
        if (connection.value !== playerConnection) return
        attachments--

        if (attachments == 0) connection.value = null
    }

    fun observeSignal(): Flow<AudioSignalData?> = connection.flatMapLatest { playerConnection ->
        if (playerConnection == null) {
            flowOf(null)
        } else {
            val service = playerConnection.service
            combine(
                playerConnection.mediaMetadata,
                service.nativeUsbAudioState,
                service.directUsbAudioState,
                service.activeAudioDevice,
            ) { metadata, native, usb, outputDevice ->
                AudioSignalData(metadata, native, usb, outputDevice)
            }
        }
    }

    fun requestUsbPermission() {
        connection.value?.service?.requestDirectUsbPermission()
    }
}
