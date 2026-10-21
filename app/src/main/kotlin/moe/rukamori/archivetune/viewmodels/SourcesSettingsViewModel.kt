package moe.rukamori.archivetune.viewmodels

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Collections
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.domain.sources.ConfigureSourcesUseCase
import moe.rukamori.archivetune.sources.SourceProvider

enum class SourceSettingsFailure { Read, Save }

@Immutable
data class SourcesSettingsUiState(
    val priority: List<SourceProvider> = Collections.unmodifiableList(SourceProvider.entries.toList()),
    val youtubeFallback: Boolean = true,
    val connected: Boolean = false,
    val phase: SourceScreenState<SourceSettingsFailure> = SourceScreenState.Loading,
)

@HiltViewModel
class SourcesSettingsViewModel @Inject constructor(private val configure: ConfigureSourcesUseCase) : ViewModel() {
    private val operation = MutableStateFlow<SourceScreenState<SourceSettingsFailure>>(SourceScreenState.Empty)
    val state = combine(configure.settings, operation) { settings, operation ->
        SourcesSettingsUiState(
            priority = Collections.unmodifiableList(settings.configuration.priority.toList()),
            youtubeFallback = settings.configuration.youtubeFallback,
            connected = settings.connectedProviders.isNotEmpty(),
            phase = when {
                settings.accounts.values.any { !it.initialized } -> SourceScreenState.Loading
                operation == SourceScreenState.Empty -> SourceScreenState.Success
                else -> operation
            },
        )
    }.catch { failure ->
        if (failure is CancellationException) throw failure
        emit(SourcesSettingsUiState(phase = SourceScreenState.Error(SourceSettingsFailure.Read)))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SourcesSettingsUiState())

    fun setPriority(priority: List<SourceProvider>) = update { configure.setPriority(priority) }
    fun setYouTubeFallback(allowed: Boolean) = update { configure.setYouTubeFallback(allowed) }

    private fun update(action: suspend () -> Unit) {
        if (operation.value == SourceScreenState.Loading) return
        operation.value = SourceScreenState.Loading
        viewModelScope.launch {
            try {
                action()
                operation.value = SourceScreenState.Success
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                operation.value = SourceScreenState.Error(SourceSettingsFailure.Save)
            }
        }
    }
}
