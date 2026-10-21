package moe.rukamori.archivetune.domain.sources

import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import moe.rukamori.archivetune.playback.PlayerConnection
import moe.rukamori.archivetune.playback.StreamingSourceData
import moe.rukamori.archivetune.playback.StreamingSourceRepository

class ObserveStreamingSourceUseCase @Inject constructor(private val repository: StreamingSourceRepository) {
    fun bind(connection: PlayerConnection) = repository.attach(connection)
    fun unbind(connection: PlayerConnection) = repository.detach(connection)

    operator fun invoke(): Flow<StreamingSourceData?> = repository.observeSource()
}
