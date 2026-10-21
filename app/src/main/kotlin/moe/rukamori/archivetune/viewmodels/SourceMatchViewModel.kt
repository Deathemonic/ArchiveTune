package moe.rukamori.archivetune.viewmodels

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Collections
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.domain.sources.ChooseSourceTrackUseCase
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.sources.SourceProvider
import moe.rukamori.archivetune.sources.SourceTrack

@Immutable
data class SourceMatchTrackUiModel(
    val id: Long,
    val title: String,
    val version: String,
    val artist: String,
    val album: String,
    val duration: Int,
    val streamable: Boolean,
    val explicit: Boolean,
)

@Immutable
sealed interface SourceSearchUiState {
    data object Loading : SourceSearchUiState
    data object Empty : SourceSearchUiState
    data object Error : SourceSearchUiState

    @Immutable
    data class Success(val tracks: List<SourceMatchTrackUiModel>) : SourceSearchUiState
}

enum class SourceMatchFailure { Selection }

@Immutable
data class SourceMatchUiState(
    val mediaId: String = "",
    val provider: SourceProvider = SourceProvider.Qobuz,
    val query: String = "",
    val search: SourceSearchUiState = SourceSearchUiState.Loading,
    val selection: SourceScreenState<SourceMatchFailure> = SourceScreenState.Empty,
) {
    val tracks: List<SourceMatchTrackUiModel> get() = (search as? SourceSearchUiState.Success)?.tracks.orEmpty()
    val searching: Boolean get() = search == SourceSearchUiState.Loading
    val saving: Boolean get() = selection == SourceScreenState.Loading
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SourceMatchViewModel @Inject constructor(private val chooseTrack: ChooseSourceTrackUseCase) : ViewModel() {
    private val metadata = MutableStateFlow<MediaMetadata?>(null)
    private val mutableState = MutableStateFlow(SourceMatchUiState())
    val state = mutableState.asStateFlow()
    val providers = chooseTrack.providerOptions.map { options ->
        if (!options.initialized) {
            emptyList()
        } else {
            if (options.providers.isEmpty()) mutableState.update { it.copy(search = SourceSearchUiState.Empty) }
            Collections.unmodifiableList(options.providers)
        }
    }.catch { failure ->
        if (failure is CancellationException) throw failure
        mutableState.update { it.copy(search = SourceSearchUiState.Error) }
        emit(emptyList())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val matches = state.map { it.provider }.distinctUntilChanged().flatMapLatest(chooseTrack::observeSelections)
    private val selectionChannel = Channel<String>(Channel.BUFFERED)
    val selections = selectionChannel.receiveAsFlow()
    private var searchJob: Job? = null
    private var searchTracks: List<SourceTrack> = emptyList()

    init {
        viewModelScope.launch {
            combine(metadata, providers, state.map { it.saving }.distinctUntilChanged()) { metadata, providers, saving ->
                Triple(metadata, providers, saving)
            }.collect { (metadata, providers, saving) ->
                if (metadata != null && !saving) {
                    val current = mutableState.value
                    val provider = current.provider.takeIf { current.mediaId == metadata.id && it in providers }
                        ?: chooseTrack.preferredProvider(metadata.id)?.takeIf { it in providers }
                        ?: providers.firstOrNull()
                    if (provider != null && (current.mediaId != metadata.id || current.provider != provider)) open(metadata, provider)
                }
            }
        }
    }

    fun bind(metadata: MediaMetadata) {
        if (this.metadata.value == null && !mutableState.value.saving) mutableState.value = SourceMatchUiState()
        this.metadata.value = metadata
    }

    fun unbind() {
        metadata.value = null
        searchJob?.cancel()
        searchTracks = emptyList()
        mutableState.update { it.copy(search = SourceSearchUiState.Empty) }
    }

    fun selectProvider(provider: SourceProvider) {
        val metadata = metadata.value ?: return
        if (provider in providers.value) open(metadata, provider)
    }

    private fun open(metadata: MediaMetadata, provider: SourceProvider) {
        if (mutableState.value.saving) return
        searchJob?.cancel()
        searchTracks = emptyList()
        if (mutableState.value.mediaId != metadata.id) {
            mutableState.value = SourceMatchUiState(mediaId = metadata.id, provider = provider, query = chooseTrack.initialQuery(metadata))
        } else {
            mutableState.update { it.copy(provider = provider, search = SourceSearchUiState.Empty, selection = SourceScreenState.Empty) }
        }
        search()
    }

    fun updateQuery(value: String) {
        if (mutableState.value.saving) return
        searchJob?.cancel()
        searchTracks = emptyList()
        mutableState.update { it.copy(query = value, search = SourceSearchUiState.Empty, selection = SourceScreenState.Empty) }
    }

    fun search() {
        val snapshot = mutableState.value
        if (snapshot.query.isBlank() || snapshot.saving) return
        searchJob?.cancel()
        searchTracks = emptyList()
        mutableState.update { it.copy(search = SourceSearchUiState.Loading, selection = SourceScreenState.Empty) }
        searchJob = viewModelScope.launch {
            try {
                val results = chooseTrack.search(snapshot.provider, snapshot.query)
                ensureActive()
                searchTracks = results.toList()
                val tracks = Collections.unmodifiableList(
                    results.map {
                        SourceMatchTrackUiModel(it.id, it.title, it.version, it.artist, it.album, it.duration, it.streamable, it.explicit)
                    },
                )
                mutableState.update { it.copy(search = if (tracks.isEmpty()) SourceSearchUiState.Empty else SourceSearchUiState.Success(tracks)) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                mutableState.update { it.copy(search = SourceSearchUiState.Error) }
            }
        }
    }

    fun select(trackId: Long?) {
        val track = trackId?.let { id -> searchTracks.firstOrNull { it.id == id } ?: return }
        val snapshot = mutableState.value
        val mediaId = snapshot.mediaId
        if (snapshot.saving || mediaId.isBlank()) return
        mutableState.update { it.copy(selection = SourceScreenState.Loading) }
        viewModelScope.launch {
            try {
                chooseTrack.select(snapshot.provider, mediaId, track)
                mutableState.update { it.copy(selection = SourceScreenState.Success) }
                selectionChannel.send(mediaId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                mutableState.update { it.copy(selection = SourceScreenState.Error(SourceMatchFailure.Selection)) }
            }
        }
    }
}
