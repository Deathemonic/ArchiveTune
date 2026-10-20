package moe.rukamori.archivetune.domain.player

import javax.inject.Inject
import moe.rukamori.archivetune.playback.PlayerConnection
import moe.rukamori.archivetune.playback.UsbVolumeRepository

data class UsbHardwareVolume(val minDb: Float, val maxDb: Float, val currentDb: Float, val failed: Boolean)

class ControlUsbVolumeUseCase @Inject constructor(private val repository: UsbVolumeRepository) {
    fun observe(connection: PlayerConnection?) = repository.observe(connection)
    fun adjust(connection: PlayerConnection?, direction: Int): Boolean = direction in -1..1 && direction != 0 && repository.adjust(connection, direction)

    fun setFraction(connection: PlayerConnection?, fraction: Float): Boolean {
        val volume = repository.currentVolume(connection) ?: return false
        if (!fraction.isFinite()) return false
        val db = volume.minDb + fraction.coerceIn(0f, 1f) * (volume.maxDb - volume.minDb)
        repository.set(connection, db)
        return true
    }
}
