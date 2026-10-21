package moe.rukamori.archivetune.viewmodels

import androidx.compose.runtime.Immutable

@Immutable
sealed interface SourceScreenState<out F> {
    data object Loading : SourceScreenState<Nothing>
    data object Success : SourceScreenState<Nothing>
    data object Empty : SourceScreenState<Nothing>

    @Immutable
    data class Error<F>(val failure: F) : SourceScreenState<F>
}

@Immutable
data class SourceAccountUiState<Q, F>(
    val connected: Boolean,
    val quality: Q,
    val phase: SourceScreenState<F>,
)
