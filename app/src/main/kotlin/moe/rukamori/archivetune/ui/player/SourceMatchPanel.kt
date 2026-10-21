package moe.rukamori.archivetune.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.sources.SourceProvider
import moe.rukamori.archivetune.ui.screens.settings.labelResId
import moe.rukamori.archivetune.utils.makeTimeString
import moe.rukamori.archivetune.viewmodels.SourceMatchUiState
import moe.rukamori.archivetune.viewmodels.SourceMatchViewModel
import moe.rukamori.archivetune.viewmodels.SourceScreenState
import moe.rukamori.archivetune.viewmodels.SourceSearchUiState

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SourceMatchPanel(
    metadata: MediaMetadata,
    onDismiss: () -> Unit,
    viewModel: SourceMatchViewModel = hiltViewModel(),
) {
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val matches by viewModel.matches.collectAsStateWithLifecycle(initialValue = emptyMap())
    val selected = matches[metadata.id]
    DisposableEffect(viewModel) {
        onDispose { viewModel.unbind() }
    }

    LaunchedEffect(viewModel, metadata) {
        viewModel.bind(metadata)
    }
    LaunchedEffect(viewModel, metadata.id) {
        viewModel.selections.collect { mediaId -> if (mediaId == metadata.id) onDismiss() }
    }

    SourceMatchContent(
        state = state,
        providers = providers,
        selectedTrackId = selected,
        onQueryChange = viewModel::updateQuery,
        onSearch = viewModel::search,
        onProviderSelected = viewModel::selectProvider,
        onSelectTrack = viewModel::select,
        onDismiss = onDismiss,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SourceMatchContent(
    state: SourceMatchUiState,
    providers: List<SourceProvider>,
    selectedTrackId: Long?,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onProviderSelected: (SourceProvider) -> Unit,
    onSelectTrack: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().imePadding().padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(painterResource(R.drawable.search), null, tint = MaterialTheme.colorScheme.primary)
            Text(
                stringResource(R.string.source_choose_track),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
            IconButton(onClick = onDismiss) {
                Icon(painterResource(R.drawable.close), stringResource(R.string.close))
            }
        }

        if (providers.isEmpty()) {
            if (state.search == SourceSearchUiState.Loading) {
                CircularProgressIndicator(Modifier.padding(20.dp).size(24.dp))
            } else {
                Text(stringResource(if (state.search == SourceSearchUiState.Error) R.string.source_match_search_failed else R.string.source_match_connect), modifier = Modifier.padding(vertical = 16.dp))
            }
            return@Column
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            modifier = Modifier.fillMaxWidth(),
        ) {
            providers.forEachIndexed { index, provider ->
                ToggleButton(
                    checked = state.provider == provider,
                    onCheckedChange = { onProviderSelected(provider) },
                    enabled = !state.saving,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shapes = when {
                        providers.size == 1 -> ToggleButtonDefaults.shapesFor(52.dp)
                        index == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        index == providers.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    colors = ToggleButtonDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                ) {
                    Text(
                        text = stringResource(provider.labelResId()),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            label = { Text(stringResource(R.string.search)) },
            singleLine = true,
            enabled = !state.saving,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            trailingIcon = {
                IconButton(
                    enabled = !state.searching && !state.saving && state.query.isNotBlank(),
                    onClick = onSearch,
                ) {
                    Icon(painterResource(R.drawable.search), stringResource(R.string.search))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        when {
            state.searching || state.saving -> CircularProgressIndicator(
                Modifier.padding(20.dp).size(24.dp),
            )

            state.selection is SourceScreenState.Error || state.search == SourceSearchUiState.Error -> Text(
                stringResource(if (state.selection is SourceScreenState.Error) R.string.source_match_save_failed else R.string.source_match_search_failed),
                Modifier.padding(top = 16.dp),
            )

            state.tracks.isEmpty() -> Text(
                stringResource(R.string.source_match_empty),
                Modifier.padding(top = 16.dp),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(state.tracks, key = { it.id }, contentType = { "source_track" }) { track ->
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = if (track.id == selectedTrackId) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(
                            enabled =
                                track.streamable && !state.saving,
                        ) { onSelectTrack(track.id) },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(14.dp),
                        ) {
                            Icon(
                                painterResource(
                                    if (track.id == selectedTrackId) {
                                        R.drawable.check
                                    } else {
                                        R.drawable.album
                                    },
                                ),
                                null,
                                Modifier.size(22.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = listOf(
                                        track.title,
                                        track.version.takeIf(String::isNotBlank),
                                    ).filterNotNull().joinToString(" · "),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${track.artist} · ${track.album}",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = if (!track.streamable) {
                                        stringResource(R.string.source_match_unavailable)
                                    } else {
                                        listOf(
                                            makeTimeString(track.duration * 1000L),
                                            if (track.explicit) {
                                                stringResource(
                                                    R.string.source_match_explicit,
                                                )
                                            } else {
                                                null
                                            },
                                        ).filterNotNull().joinToString(" · ")
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (selectedTrackId != null) {
            TextButton(enabled = !state.saving, onClick = { onSelectTrack(null) }) {
                Text(stringResource(R.string.source_match_automatic))
            }
        }
    }
}
