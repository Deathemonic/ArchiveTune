package moe.rukamori.archivetune.domain.player

import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import moe.rukamori.archivetune.playback.AudioSignalData
import moe.rukamori.archivetune.playback.AudioSignalRepository
import moe.rukamori.archivetune.playback.PlayerConnection

class ObserveAudioSignalUseCase @Inject constructor(private val repository: AudioSignalRepository) {
    fun bind(connection: PlayerConnection) = repository.attach(connection)
    fun unbind(connection: PlayerConnection) = repository.detach(connection)
    fun requestUsbPermission() = repository.requestUsbPermission()

    operator fun invoke(): Flow<AudioSignalData?> = repository.observeSignal()
}
