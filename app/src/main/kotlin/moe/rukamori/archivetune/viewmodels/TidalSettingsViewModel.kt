package moe.rukamori.archivetune.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.domain.sources.ManageTidalAccountUseCase
import moe.rukamori.archivetune.sources.SourceStorageException
import moe.rukamori.archivetune.sources.SourceStorageState
import moe.rukamori.archivetune.sources.tidal.TidalException
import moe.rukamori.archivetune.sources.tidal.TidalFailure
import moe.rukamori.archivetune.sources.tidal.TidalQuality

@HiltViewModel
class TidalSettingsViewModel @Inject constructor(private val account: ManageTidalAccountUseCase) : ViewModel() {
    private val settings = account.settings

    private val operation = MutableStateFlow<SourceScreenState<TidalFailure>>(SourceScreenState.Empty)
    val state = combine(settings, account.storageState, operation) { settings, storage, operation ->
        val phase = when {
            storage == SourceStorageState.Loading || operation == SourceScreenState.Loading -> SourceScreenState.Loading
            operation is SourceScreenState.Error -> operation
            storage is SourceStorageState.Error -> SourceScreenState.Error(TidalFailure.Storage)
            settings.connected -> SourceScreenState.Success
            else -> SourceScreenState.Empty
        }
        SourceAccountUiState(settings.connected, settings.quality, phase)
    }.catch { failure ->
        if (failure is CancellationException) throw failure
        emit(SourceAccountUiState(settings.value.connected, settings.value.quality, SourceScreenState.Error(TidalFailure.Storage)))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SourceAccountUiState(false, TidalQuality.Max, SourceScreenState.Loading))

    fun setQuality(quality: TidalQuality) = runOperation { account.setQuality(quality) }

    fun disconnect() = runOperation { account.disconnect() }

    fun clearFailure() {
        operation.value = SourceScreenState.Empty
    }

    private fun runOperation(action: suspend () -> Unit) {
        if (operation.value == SourceScreenState.Loading) return

        operation.value = SourceScreenState.Loading

        viewModelScope.launch {
            try {
                action()
                operation.value = SourceScreenState.Success
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                operation.value = SourceScreenState.Error(
                    when (failure) {
                        is SourceStorageException -> TidalFailure.Storage
                        is TidalException -> failure.reason
                        is IllegalArgumentException -> TidalFailure.Account
                        else -> TidalFailure.Network
                    },
                )
            }
        }
    }
}
