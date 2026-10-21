package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.sources.tidal.TidalFailure
import moe.rukamori.archivetune.sources.tidal.TidalQuality
import moe.rukamori.archivetune.ui.component.DefaultDialog
import moe.rukamori.archivetune.ui.component.ListPreference
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.viewmodels.SourceScreenState
import moe.rukamori.archivetune.viewmodels.TidalSettingsViewModel

@Composable
fun TidalAccountSettings(onLogin: () -> Unit, viewModel: TidalSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val phase = state.phase
    val busy = phase == SourceScreenState.Loading
    val failure = (phase as? SourceScreenState.Error)?.failure
    val form = remember { SourceAccountFormState() }

    TidalAccountContent(state.connected, busy, failure, form, viewModel::clearFailure, viewModel::disconnect, onLogin)
}

@Composable
private fun TidalAccountContent(
    connected: Boolean,
    busy: Boolean,
    failure: TidalFailure?,
    form: SourceAccountFormState,
    onClearFailure: () -> Unit,
    onDisconnect: () -> Unit,
    onLogin: () -> Unit,
) {
    PreferenceEntry(
        title = { Text(stringResource(R.string.tidal)) },
        description = if (failure != null) tidalFailureText(failure) else stringResource(if (connected) R.string.sources_connected else R.string.sources_not_connected),
        icon = { Icon(painterResource(R.drawable.tidal), null) },
        isEnabled = !busy,
        onClick = {
            onClearFailure()

            form.showLogin = true
        },
    )

    if (form.showLogin) {
        DefaultDialog(
            onDismiss = { form.showLogin = false },
            modifier = Modifier.widthIn(max = 400.dp).fillMaxWidth(),
            icon = { Icon(painterResource(R.drawable.account), null) },
            title = { Text(stringResource(R.string.tidal_connect)) },
            buttons = {
                if (connected) {
                    TextButton(enabled = !busy, onClick = {
                        onDisconnect()
                        form.showLogin = false
                    }) { Text(stringResource(R.string.sources_disconnect)) }
                }
                TextButton(enabled = !busy, onClick = { form.showLogin = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
                TextButton(enabled = !busy, onClick = {
                    form.showLogin = false
                    onLogin()
                }) { Text(stringResource(R.string.tidal_connect)) }
            },
        ) { Text(stringResource(R.string.tidal_login_description)) }
    }
}

@Composable
fun TidalQualityPreference(viewModel: TidalSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val phase = state.phase
    val busy = phase == SourceScreenState.Loading

    ListPreference(
        title = { Text(stringResource(R.string.tidal)) },
        description = if (state.connected) null else stringResource(R.string.tidal_quality_locked),
        icon = { Icon(painterResource(if (state.connected) R.drawable.tidal else R.drawable.lock), null) },
        selectedValue = state.quality,
        values = TidalQuality.entries.toList(),
        valueText = { quality ->
            stringResource(
                when (quality) {
                    TidalQuality.Low -> R.string.audio_quality_low
                    TidalQuality.High -> R.string.audio_quality_high
                    TidalQuality.Max -> R.string.tidal_quality_max
                },
            )
        },
        valueDescription = { quality ->
            stringResource(
                when (quality) {
                    TidalQuality.Low -> R.string.tidal_quality_low_description
                    TidalQuality.High -> R.string.tidal_quality_high_description
                    TidalQuality.Max -> R.string.tidal_quality_max_description
                },
            )
        },
        isEnabled = state.connected && !busy,
        onValueSelected = viewModel::setQuality,
    )
}

@Composable
internal fun tidalFailureText(failure: TidalFailure?): String = stringResource(
    when (failure) {
        TidalFailure.Expired -> R.string.tidal_error_expired
        TidalFailure.Denied -> R.string.tidal_error_denied
        TidalFailure.Storage -> R.string.sources_storage_error
        TidalFailure.Account -> R.string.tidal_error_account
        TidalFailure.Subscription -> R.string.tidal_error_subscription
        TidalFailure.Unavailable -> R.string.tidal_error_unavailable
        TidalFailure.Network, null -> R.string.tidal_error_network
    },
)
