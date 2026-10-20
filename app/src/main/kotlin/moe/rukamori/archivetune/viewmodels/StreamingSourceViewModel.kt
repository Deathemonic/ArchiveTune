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
import moe.rukamori.archivetune.domain.sources.ObserveStreamingSourceUseCase
import moe.rukamori.archivetune.playback.PlayerConnection
import moe.rukamori.archivetune.ui.player.StreamingSourceUiModel
import moe.rukamori.archivetune.ui.player.toStreamingSourceUiModel

@Immutable
sealed interface StreamingSourceUiState {
    val model: StreamingSourceUiModel? get() = null
    data object Loading : StreamingSourceUiState
    data object Empty : StreamingSourceUiState
    data object Error : StreamingSourceUiState

    @Immutable
    data class Success(override val model: StreamingSourceUiModel) : StreamingSourceUiState
}

@HiltViewModel
class StreamingSourceViewModel @Inject constructor(private val observeSignal: ObserveStreamingSourceUseCase) : ViewModel() {
    private val failed = MutableStateFlow(false)
    private val signal = observeSignal().map { data ->
        data?.let { StreamingSourceUiState.Success(it.toStreamingSourceUiModel()) } ?: StreamingSourceUiState.Empty
    }.catch { failure ->
        if (failure is CancellationException) throw failure
        emit(StreamingSourceUiState.Error)
    }
    val state = combine(signal, failed) { signal, failed -> if (failed) StreamingSourceUiState.Error else signal }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StreamingSourceUiState.Loading)

    fun bind(connection: PlayerConnection) = perform { observeSignal.bind(connection) }
    fun unbind(connection: PlayerConnection) = perform { observeSignal.unbind(connection) }

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
