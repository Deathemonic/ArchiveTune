package moe.rukamori.archivetune.viewmodels

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import moe.rukamori.archivetune.domain.player.ModulePlaybackIndicator
import moe.rukamori.archivetune.domain.player.ObserveModulePlaybackSourceUseCase
import moe.rukamori.archivetune.playback.PlayerConnection
import moe.rukamori.archivetune.utils.isLocalMediaId

@Immutable
data class ModuleSourcePillUiModel(val moduleId: String?, val moduleName: String?)

@Immutable
sealed interface ModuleSourcePillState {
    data object Loading : ModuleSourcePillState
    data object Empty : ModuleSourcePillState
    data object Error : ModuleSourcePillState
    data class Success(val model: ModuleSourcePillUiModel) : ModuleSourcePillState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ModulePlaybackSourceViewModel @Inject constructor(observe: ObserveModulePlaybackSourceUseCase) : ViewModel() {
    private val connection = MutableStateFlow<PlayerConnection?>(null)
    val state = connection.flatMapLatest { player ->
        observe(player?.mediaMetadata?.map { metadata ->
            metadata?.takeUnless { it.isPodcast || it.id.isLocalMediaId() }?.id
        } ?: kotlinx.coroutines.flow.flowOf(null)).map { source ->
            when (source) {
                ModulePlaybackIndicator.Hidden -> ModuleSourcePillState.Empty
                ModulePlaybackIndicator.YouTube -> ModuleSourcePillState.Success(ModuleSourcePillUiModel(null, null))
                is ModulePlaybackIndicator.Module -> ModuleSourcePillState.Success(ModuleSourcePillUiModel(source.source.id, source.source.name))
            }
        }.catch { failure ->
            if (failure is CancellationException) throw failure
            emit(ModuleSourcePillState.Error)
        }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ModuleSourcePillState.Loading)

    fun bind(player: PlayerConnection) { connection.value = player }
    fun unbind(player: PlayerConnection) { if (connection.value === player) connection.value = null }
}
