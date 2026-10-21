package moe.rukamori.archivetune.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.domain.sources.ManageQobuzAccountUseCase
import moe.rukamori.archivetune.sources.SourceStorageException
import moe.rukamori.archivetune.sources.SourceStorageState
import moe.rukamori.archivetune.sources.qobuz.QobuzApiException
import moe.rukamori.archivetune.sources.qobuz.QobuzFailure
import moe.rukamori.archivetune.sources.qobuz.QobuzFailureReason
import moe.rukamori.archivetune.sources.qobuz.QobuzQuality

@HiltViewModel
class QobuzSettingsViewModel
@Inject
constructor(
    private val account: ManageQobuzAccountUseCase,
) : ViewModel() {
    private val settings = account.settings
    private val operation = MutableStateFlow<SourceScreenState<QobuzFailure>>(SourceScreenState.Empty)
    val state = combine(settings, account.storageState, operation) { settings, storage, operation ->
        val phase = when {
            storage == SourceStorageState.Loading || operation == SourceScreenState.Loading -> SourceScreenState.Loading
            operation is SourceScreenState.Error -> operation
            storage is SourceStorageState.Error -> SourceScreenState.Error(QobuzFailure(QobuzFailureReason.Storage))
            settings.connected -> SourceScreenState.Success
            else -> SourceScreenState.Empty
        }
        SourceAccountUiState(settings.connected, settings.quality, phase)
    }.catch { failure ->
        if (failure is CancellationException) throw failure
        emit(SourceAccountUiState(settings.value.connected, settings.value.quality, SourceScreenState.Error(QobuzFailure(QobuzFailureReason.Storage))))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SourceAccountUiState(false, QobuzQuality.Master, SourceScreenState.Loading))

    private val mutableConnectionRevision = MutableStateFlow(0)
    val connectionRevision = mutableConnectionRevision.asStateFlow()

    fun connect(appId: String, appSecret: String, token: String) = runOperation {
        account.connect(appId, appSecret, token)
        mutableConnectionRevision.value += 1
    }

    fun updateQuality(quality: QobuzQuality) = runOperation {
        account.setQuality(quality)
    }

    fun disconnect() = runOperation { account.disconnect() }

    fun clearError() {
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
                        is QobuzApiException -> failure.failure
                        is SourceStorageException -> QobuzFailure(QobuzFailureReason.Storage)
                        else -> QobuzFailure(QobuzFailureReason.Network)
                    },
                )
            }
        }
    }
}
