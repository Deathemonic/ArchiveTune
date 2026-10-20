package moe.rukamori.archivetune.viewmodels

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.domain.sources.ManageTidalAccountUseCase
import moe.rukamori.archivetune.sources.SourceStorageException
import moe.rukamori.archivetune.sources.tidal.TidalException
import moe.rukamori.archivetune.sources.tidal.TidalFailure

@Immutable
sealed interface TidalLoginUiState {
    data object Loading : TidalLoginUiState

    @Immutable
    data class Authorizing(val verificationUrl: String) : TidalLoginUiState
    data object Success : TidalLoginUiState

    @Immutable
    data class Error(val failure: TidalFailure) : TidalLoginUiState
}

@HiltViewModel
class TidalLoginViewModel @Inject constructor(private val account: ManageTidalAccountUseCase) : ViewModel() {
    private val mutableState = MutableStateFlow<TidalLoginUiState>(TidalLoginUiState.Loading)
    val state = mutableState.asStateFlow()
    private var loginJob: Job? = null

    init {
        retry()
    }

    fun retry() {
        loginJob?.cancel()
        mutableState.value = TidalLoginUiState.Loading
        loginJob = viewModelScope.launch {
            try {
                val authorization = account.beginLogin()
                mutableState.value = TidalLoginUiState.Authorizing(authorization.verificationUrl)
                account.completeLogin(authorization)
                mutableState.value = TidalLoginUiState.Success
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                mutableState.value = TidalLoginUiState.Error(
                    when (failure) {
                        is TidalException -> failure.reason
                        is SourceStorageException -> TidalFailure.Storage
                        else -> TidalFailure.Network
                    },
                )
            }
        }
    }

    fun onWebFailure() {
        loginJob?.cancel()
        mutableState.value = TidalLoginUiState.Error(TidalFailure.Network)
    }
}
