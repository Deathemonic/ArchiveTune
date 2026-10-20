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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import moe.rukamori.archivetune.domain.player.ControlUsbVolumeUseCase
import moe.rukamori.archivetune.playback.PlayerConnection

@Immutable
data class UsbVolumeUiModel(val minDb: Float, val maxDb: Float, val currentDb: Float, val failed: Boolean)

@Immutable
sealed interface UsbVolumeUiState {
    data object Empty : UsbVolumeUiState
    data object Error : UsbVolumeUiState

    @Immutable
    data class Success(val volume: UsbVolumeUiModel) : UsbVolumeUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class UsbVolumeViewModel @Inject constructor(private val control: ControlUsbVolumeUseCase) : ViewModel() {
    private val connection = MutableStateFlow<PlayerConnection?>(null)
    private val failed = MutableStateFlow(false)
    private var attachments = 0
    private val volume = connection.flatMapLatest(control::observe).map { volume ->
        volume?.let { UsbVolumeUiState.Success(UsbVolumeUiModel(it.minDb, it.maxDb, it.currentDb, it.failed)) } ?: UsbVolumeUiState.Empty
    }.catch { failure ->
        if (failure is CancellationException) throw failure
        emit(UsbVolumeUiState.Error)
    }
    val state = combine(volume, failed) { volume, failed -> if (failed) UsbVolumeUiState.Error else volume }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UsbVolumeUiState.Empty)

    fun bind(connection: PlayerConnection) {
        attachments = if (this.connection.value === connection) attachments + 1 else 1
        this.connection.value = connection
    }

    fun unbind(connection: PlayerConnection) {
        if (this.connection.value !== connection) return
        attachments--
        if (attachments == 0) this.connection.value = null
    }

    fun adjust(connection: PlayerConnection?, direction: Int): Boolean = perform { control.adjust(connection, direction) }
    fun setFraction(fraction: Float) = perform { control.setFraction(connection.value, fraction) }

    private inline fun perform(action: () -> Boolean): Boolean = try {
        action().also { failed.value = false }
    } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        failed.value = true
        false
    }
}
