package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.sources.deezer.DeezerFailure
import moe.rukamori.archivetune.sources.deezer.DeezerQuality
import moe.rukamori.archivetune.ui.component.DefaultDialog
import moe.rukamori.archivetune.ui.component.ListPreference
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.viewmodels.DeezerSettingsViewModel
import moe.rukamori.archivetune.viewmodels.SourceScreenState

@Composable
fun DeezerAccountSettings(viewModel: DeezerSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val phase = state.phase
    val busy = phase == SourceScreenState.Loading
    val failure = (phase as? SourceScreenState.Error)?.failure
    val revision by viewModel.connectionRevision.collectAsStateWithLifecycle()
    val form = remember { SourceAccountFormState() }

    LaunchedEffect(revision) {
        if (revision > 0) form.dismiss()
    }

    DeezerAccountContent(state.connected, busy, failure, form, viewModel::clearFailure, viewModel::connect, viewModel::disconnect)
}

@Composable
private fun DeezerAccountContent(
    connected: Boolean,
    busy: Boolean,
    failure: DeezerFailure?,
    form: SourceAccountFormState,
    onClearFailure: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
) {
    PreferenceEntry(
        title = { Text(stringResource(R.string.deezer)) },
        description = if (failure != null && !form.showLogin) deezerFailureText(failure) else stringResource(if (connected) R.string.sources_connected else R.string.sources_not_connected),
        icon = { Icon(painterResource(R.drawable.deezer), null) },
        isEnabled = !busy,
        onClick = {
            onClearFailure()
            form.showLogin = true
        },
    )

    if (form.showLogin) {
        val dismiss = {
            if (!busy) {
                form.dismiss()
            }
        }
        DefaultDialog(
            onDismiss = dismiss,
            modifier = Modifier.widthIn(max = 400.dp).fillMaxWidth(),
            icon = { Icon(painterResource(R.drawable.account), null) },
            title = { Text(stringResource(R.string.deezer_connect)) },
            contentScrollable = true,
            buttons = {
                if (connected) {
                    TextButton(enabled = !busy, onClick = {
                        dismiss()
                        form.showDisconnect = true
                    }) { Text(stringResource(R.string.sources_disconnect)) }
                }
                TextButton(enabled = !busy, onClick = dismiss) { Text(stringResource(android.R.string.cancel)) }
                TextButton(enabled = !busy && form.token.trim().length in 64..512, onClick = { onConnect(form.token) }) {
                    Text(stringResource(if (busy) R.string.connecting else R.string.deezer_connect))
                }
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.deezer_login_description))
                OutlinedTextField(
                    value = form.token,
                    onValueChange = { form.token = it },
                    label = { Text(stringResource(R.string.deezer_arl), maxLines = 1) },
                    leadingIcon = { Icon(painterResource(R.drawable.lock), null) },
                    trailingIcon = {
                        IconButton(onClick = { form.tokenVisible = !form.tokenVisible }, enabled = !busy) {
                            Icon(
                                painterResource(if (form.tokenVisible) R.drawable.visibility_off else R.drawable.visibility),
                                stringResource(if (form.tokenVisible) R.string.qobuz_hide_credential else R.string.qobuz_show_credential),
                            )
                        }
                    },
                    visualTransformation = if (form.tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (failure != null) Text(deezerFailureText(failure), color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (form.showDisconnect) {
        DefaultDialog(
            onDismiss = { form.showDisconnect = false },
            modifier = Modifier.widthIn(max = 400.dp).fillMaxWidth(),
            title = { Text(stringResource(R.string.deezer_disconnect)) },
            icon = { Icon(painterResource(R.drawable.logout), null) },
            buttons = {
                TextButton(onClick = { form.showDisconnect = false }) { Text(stringResource(android.R.string.cancel)) }
                TextButton(enabled = !busy, onClick = {
                    onDisconnect()
                    form.showDisconnect = false
                }) { Text(stringResource(R.string.deezer_disconnect)) }
            },
        ) { Text(stringResource(R.string.deezer_disconnect_description)) }
    }
}

@Composable
fun DeezerQualityPreference(viewModel: DeezerSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val phase = state.phase
    val busy = phase == SourceScreenState.Loading
    ListPreference(
        title = { Text(stringResource(R.string.deezer)) },
        description = if (state.connected) null else stringResource(R.string.deezer_quality_locked),
        icon = { Icon(painterResource(if (state.connected) R.drawable.deezer else R.drawable.lock), null) },
        selectedValue = state.quality,
        values = DeezerQuality.entries.toList(),
        valueText = { quality ->
            stringResource(
                when (quality) {
                    DeezerQuality.Standard -> R.string.qobuz_quality_standard
                    DeezerQuality.High -> R.string.audio_quality_high
                    DeezerQuality.HiFi -> R.string.deezer_quality_hifi
                },
            )
        },
        valueDescription = { quality ->
            stringResource(
                when (quality) {
                    DeezerQuality.Standard -> R.string.deezer_quality_standard_description
                    DeezerQuality.High -> R.string.deezer_quality_high_description
                    DeezerQuality.HiFi -> R.string.deezer_quality_hifi_description
                },
            )
        },
        isEnabled = state.connected && !busy,
        onValueSelected = viewModel::setQuality,
    )
}

@Composable
private fun deezerFailureText(failure: DeezerFailure?): String = stringResource(
    when (failure) {
        DeezerFailure.Storage -> R.string.sources_storage_error
        DeezerFailure.Account -> R.string.deezer_error_account
        DeezerFailure.Subscription -> R.string.deezer_error_subscription
        DeezerFailure.Unavailable -> R.string.deezer_error_unavailable
        DeezerFailure.Network, null -> R.string.deezer_error_network
    },
)
