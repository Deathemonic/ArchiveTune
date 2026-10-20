package moe.rukamori.archivetune.viewmodels

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.domain.player.AudioBypassMode
import moe.rukamori.archivetune.domain.player.ConfigureAudioOutputUseCase

@Immutable
sealed interface NativeUsbAudioSettingsUiState {
    val mode: AudioBypassMode?

    @Immutable
    data class Loading(override val mode: AudioBypassMode? = null) : NativeUsbAudioSettingsUiState

    @Immutable
    data class Success(override val mode: AudioBypassMode) : NativeUsbAudioSettingsUiState

    @Immutable
    data class Error(override val mode: AudioBypassMode?) : NativeUsbAudioSettingsUiState
}

@HiltViewModel
class NativeUsbAudioSettingsViewModel @Inject constructor(private val configure: ConfigureAudioOutputUseCase) : ViewModel() {
    private val mutableState = MutableStateFlow<NativeUsbAudioSettingsUiState>(NativeUsbAudioSettingsUiState.Loading())
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            configure.mode.catch { failure ->
                if (failure is CancellationException) throw failure
                mutableState.value = NativeUsbAudioSettingsUiState.Error(mutableState.value.mode)
            }.collect { mode ->
                mutableState.value = NativeUsbAudioSettingsUiState.Success(mode)
            }
        }
    }

    fun setMode(mode: AudioBypassMode) {
        val previous = mutableState.value
        if (previous is NativeUsbAudioSettingsUiState.Loading) return
        mutableState.value = NativeUsbAudioSettingsUiState.Loading(previous.mode)
        viewModelScope.launch {
            try {
                configure.setMode(mode)
                mutableState.value = NativeUsbAudioSettingsUiState.Success(mode)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                mutableState.value = NativeUsbAudioSettingsUiState.Error(previous.mode)
            }
        }
    }
}
