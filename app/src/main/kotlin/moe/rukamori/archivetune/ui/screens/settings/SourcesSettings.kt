package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.sources.SourceProvider
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.PreferenceGroup
import moe.rukamori.archivetune.ui.component.SwitchPreference
import moe.rukamori.archivetune.ui.player.StreamingSourceUiModel
import moe.rukamori.archivetune.ui.player.formatSampleRate
import moe.rukamori.archivetune.ui.player.sourceFallbackDescription
import moe.rukamori.archivetune.ui.screens.TIDAL_LOGIN_ROUTE
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.viewmodels.SourceScreenState
import moe.rukamori.archivetune.viewmodels.SourceSettingsFailure
import moe.rukamori.archivetune.viewmodels.SourcesSettingsViewModel
import moe.rukamori.archivetune.viewmodels.StreamingSourceViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesSettings(
    navController: NavController,
    viewModel: SourcesSettingsViewModel = hiltViewModel(),
    signalViewModel: StreamingSourceViewModel = hiltViewModel(key = "source-settings-signal"),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy = state.phase == SourceScreenState.Loading
    val failure = (state.phase as? SourceScreenState.Error)?.failure
    val signal by signalViewModel.state.collectAsStateWithLifecycle()
    val connection = LocalPlayerConnection.current
    val snackbar = remember { SnackbarHostState() }
    val failureText = stringResource(if (failure == SourceSettingsFailure.Read) R.string.sources_storage_error else R.string.sources_save_failed)
    var showPriority by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(connection, signalViewModel) {
        connection?.let(signalViewModel::bind)
        onDispose { connection?.let(signalViewModel::unbind) }
    }

    LaunchedEffect(failure) {
        if (failure != null) snackbar.showSnackbar(failureText)
    }

    if (showPriority) {
        SourcePriorityDialog(
            initialOrder = state.priority,
            onDismiss = { showPriority = false },
            onConfirm = {
                viewModel.setPriority(it)
                showPriority = false
            },
        )
    }

    SourcesSettingsContent(
        priority = state.priority,
        youtubeFallback = state.youtubeFallback,
        connected = state.connected,
        busy = busy,
        signal = signal.model,
        snackbar = snackbar,
        onBack = navController::navigateUp,
        onBackToMain = navController::backToMain,
        onShowPriority = { showPriority = true },
        onYouTubeFallbackChanged = viewModel::setYouTubeFallback,
        onTidalLogin = { navController.navigate(TIDAL_LOGIN_ROUTE) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourcesSettingsContent(
    priority: List<SourceProvider>,
    youtubeFallback: Boolean,
    connected: Boolean,
    busy: Boolean,
    signal: StreamingSourceUiModel?,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onBackToMain: () -> Unit,
    onShowPriority: () -> Unit,
    onYouTubeFallbackChanged: (Boolean) -> Unit,
    onTidalLogin: () -> Unit,
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sources)) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        onLongClick = onBackToMain,
                    ) {
                        Icon(painterResource(R.drawable.arrow_back), null)
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            Modifier
                .padding(top = innerPadding.calculateTopPadding())
                .consumeWindowInsets(PaddingValues(top = innerPadding.calculateTopPadding()))
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .verticalScroll(rememberScrollState())
                .padding(bottom = SettingsDimensions.ScreenBottomPadding),
        ) {
            PreferenceGroup(title = stringResource(R.string.sources_accounts)) {
                item { QobuzAccountSettings() }
                item { DeezerAccountSettings() }
                item { TidalAccountSettings(onLogin = onTidalLogin) }
            }
            PreferenceGroup(title = stringResource(R.string.sources_playback)) {
                item {
                    val order = priority.map { stringResource(it.labelResId()) } +
                        stringResource(R.string.audio_source_youtube)
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.source_priority)) },
                        description = order.joinToString(" · "),
                        icon = { Icon(painterResource(R.drawable.drag_handle), null) },
                        isEnabled = !busy,
                        onClick = onShowPriority,
                    )
                }
                item {
                    SwitchPreference(
                        title = { Text(stringResource(R.string.sources_youtube_fallback)) },
                        description = stringResource(R.string.sources_fallback_description),
                        icon = { Icon(painterResource(R.drawable.cached), null) },
                        checked = youtubeFallback,
                        isEnabled = connected && !busy,
                        onCheckedChange = onYouTubeFallbackChanged,
                    )
                }
                item {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.sources_streaming_source)) },
                        description = sourceDescription(signal),
                        icon = { Icon(painterResource(signal?.source?.iconRes ?: R.drawable.info), null) },
                    )
                }
            }
            PreferenceGroup(title = stringResource(R.string.sources_quality)) {
                item { QobuzQualityPreference() }
                item { DeezerQualityPreference() }
                item { TidalQualityPreference() }
            }
        }
    }
}

@Composable
private fun sourceDescription(signal: StreamingSourceUiModel?): String {
    if (signal?.title == null) return stringResource(R.string.sources_status_idle)

    val format = listOfNotNull(
        stringResource(signal.source.labelRes),
        signal.bitDepth?.let { stringResource(R.string.audio_bit_depth, it) } ?: signal.codec,
        signal.sampleRate?.let { formatSampleRate(it) },
    ).joinToString(" · ")

    return listOfNotNull(format, sourceFallbackDescription(signal)).joinToString("\n")
}
