package moe.rukamori.archivetune.playback

import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import moe.rukamori.archivetune.domain.player.UsbHardwareVolume

class UsbVolumeRepository @Inject constructor() {
    fun currentVolume(connection: PlayerConnection?): UsbHardwareVolume? = connection?.service?.directUsbAudioState?.value?.toVolume()

    fun observe(connection: PlayerConnection?): Flow<UsbHardwareVolume?> = connection?.service?.directUsbAudioState?.map { it.toVolume() } ?: flowOf(null)

    fun adjust(connection: PlayerConnection?, direction: Int): Boolean = connection?.service?.adjustUsbHardwareVolume(direction) == true

    fun set(connection: PlayerConnection?, db: Float) {
        connection?.service?.setUsbHardwareVolume(db)
    }

    private fun DirectUsbAudioState.toVolume(): UsbHardwareVolume? = volume?.takeIf { enabled && stream != null && it.maxDb > it.minDb }?.let {
        UsbHardwareVolume(it.minDb, it.maxDb, it.currentDb, volumeError)
    }
}
