package moe.rukamori.archivetune.sources

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class SourceStorageException(cause: Exception) : IOException("Unable to persist source data", cause)

sealed interface SourceStorageState {
    data object Loading : SourceStorageState
    data object Ready : SourceStorageState
    data class Error(val accountFailed: Boolean, val matchesFailed: Boolean) : SourceStorageState
}

internal class SourceStorageLoader<C, S>(
    scope: CoroutineScope,
    defaultSettings: S,
    readAccount: () -> Pair<C?, S>,
    readMatches: () -> SourceMatchReadResult,
    apply: (Pair<C?, S>, Map<String, SourceTrack>) -> Unit,
) {
    private val mutableState = MutableStateFlow<SourceStorageState>(SourceStorageState.Loading)
    val state = mutableState.asStateFlow()
    private val initialization = scope.launch(Dispatchers.IO) {
        var accountFailed = false
        var matchesFailed = false
        val account = try {
            readAccount()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            accountFailed = true
            null to defaultSettings
        }
        val matches = try {
            readMatches().also { matchesFailed = it.invalidRecords > 0 }.tracks
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            matchesFailed = true
            emptyMap()
        }
        try {
            apply(account, matches)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            accountFailed = true
            apply(null to defaultSettings, matches)
        }
        mutableState.value = if (accountFailed || matchesFailed) SourceStorageState.Error(accountFailed, matchesFailed) else SourceStorageState.Ready
    }

    suspend fun awaitInitialization() = initialization.join()

    fun accountUpdated() {
        mutableState.update {
            if (it is SourceStorageState.Error && it.matchesFailed) SourceStorageState.Error(false, true) else SourceStorageState.Ready
        }
    }
}
