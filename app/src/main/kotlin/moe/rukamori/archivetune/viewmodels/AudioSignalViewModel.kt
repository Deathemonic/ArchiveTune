package moe.rukamori.archivetune.viewmodels

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import moe.rukamori.archivetune.domain.player.ObserveAudioSignalUseCase
import moe.rukamori.archivetune.playback.PlayerConnection
import moe.rukamori.archivetune.ui.player.AudioSignalUiModel
import moe.rukamori.archivetune.ui.player.toAudioSignalUiModel

@Immutable
sealed interface AudioSignalUiState {
    val model: AudioSignalUiModel? get() = null
    data object Loading : AudioSignalUiState
    data object Empty : AudioSignalUiState
    data object Error : AudioSignalUiState

    @Immutable
    data class Success(override val model: AudioSignalUiModel) : AudioSignalUiState
}

@HiltViewModel
class AudioSignalViewModel @Inject constructor(private val observeSignal: ObserveAudioSignalUseCase) : ViewModel() {
    private val failed = MutableStateFlow(false)
    private val signal = observeSignal().map { data ->
        data?.let { AudioSignalUiState.Success(it.toAudioSignalUiModel()) } ?: AudioSignalUiState.Empty
    }.catch { failure ->
        if (failure is CancellationException) throw failure
        emit(AudioSignalUiState.Error)
    }
    val state = combine(signal, failed) { signal, failed -> if (failed) AudioSignalUiState.Error else signal }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AudioSignalUiState.Loading)

    fun bind(connection: PlayerConnection) = perform { observeSignal.bind(connection) }
    fun unbind(connection: PlayerConnection) = perform { observeSignal.unbind(connection) }
    fun requestUsbPermission() = perform { observeSignal.requestUsbPermission() }

    private inline fun perform(action: () -> Unit) {
        try {
            action()
            failed.value = false
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            failed.value = true
        }
    }
}
