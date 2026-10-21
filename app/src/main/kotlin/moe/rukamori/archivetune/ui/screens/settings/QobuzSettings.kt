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
import moe.rukamori.archivetune.sources.qobuz.QobuzFailure
import moe.rukamori.archivetune.sources.qobuz.QobuzFailureReason
import moe.rukamori.archivetune.sources.qobuz.QobuzQuality
import moe.rukamori.archivetune.sources.qobuz.QobuzRequestStage
import moe.rukamori.archivetune.ui.component.DefaultDialog
import moe.rukamori.archivetune.ui.component.ListPreference
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.viewmodels.QobuzSettingsViewModel
import moe.rukamori.archivetune.viewmodels.SourceScreenState

@Composable
fun QobuzAccountSettings(viewModel: QobuzSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val phase = state.phase
    val busy = phase == SourceScreenState.Loading
    val connectionRevision by viewModel.connectionRevision.collectAsStateWithLifecycle()
    val failed = (phase as? SourceScreenState.Error)?.failure
    val form = remember { SourceAccountFormState() }

    LaunchedEffect(connectionRevision) {
        if (connectionRevision > 0) form.dismiss()
    }

    QobuzAccountContent(state.connected, busy, failed, form, viewModel::clearError, viewModel::connect, viewModel::disconnect)
}

@Composable
private fun QobuzAccountContent(
    connected: Boolean,
    busy: Boolean,
    failed: QobuzFailure?,
    form: SourceAccountFormState,
    onClearFailure: () -> Unit,
    onConnect: (String, String, String) -> Unit,
    onDisconnect: () -> Unit,
) {
    PreferenceEntry(
        title = { Text(stringResource(R.string.qobuz)) },
        description = if (failed != null && !form.showLogin) qobuzFailureText(failed) else stringResource(if (connected) R.string.sources_connected else R.string.sources_not_connected),
        icon = { Icon(painterResource(R.drawable.album), null) },
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
            title = { Text(stringResource(if (connected) R.string.qobuz_update_credentials else R.string.qobuz_connect)) },
            contentScrollable = true,
            buttons = {
                if (connected) {
                    TextButton(enabled = !busy, onClick = {
                        dismiss()
                        form.showDisconnect = true
                    }) { Text(stringResource(R.string.sources_disconnect)) }
                }
                TextButton(enabled = !busy, onClick = dismiss) { Text(stringResource(android.R.string.cancel)) }
                TextButton(
                    enabled = !busy && form.appId.isNotBlank() && form.appSecret.isNotBlank() && form.token.isNotBlank(),
                    onClick = { onConnect(form.appId, form.appSecret, form.token) },
                ) {
                    Text(
                        stringResource(
                            if (busy) {
                                R.string.qobuz_connecting
                            } else if (connected) {
                                R.string.save
                            } else {
                                R.string.qobuz_connect
                            },
                        ),
                    )
                }
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                QobuzCredentialField(form.appId, { form.appId = it }, stringResource(R.string.qobuz_app_id), R.drawable.token, !busy)
                QobuzCredentialField(form.appSecret, { form.appSecret = it }, stringResource(R.string.qobuz_app_secret), R.drawable.lock, !busy, secret = true, visible = form.secretVisible, onToggleVisibility = { form.secretVisible = !form.secretVisible })
                QobuzCredentialField(form.token, { form.token = it }, stringResource(R.string.qobuz_user_token), R.drawable.account, !busy, secret = true, visible = form.tokenVisible, onToggleVisibility = { form.tokenVisible = !form.tokenVisible })

                if (failed != null) Text(qobuzFailureText(failed), color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (form.showDisconnect) {
        DefaultDialog(
            onDismiss = { form.showDisconnect = false },
            modifier = Modifier.widthIn(max = 400.dp).fillMaxWidth(),
            icon = { Icon(painterResource(R.drawable.logout), null) },
            title = { Text(stringResource(R.string.qobuz_disconnect)) },
            buttons = {
                TextButton(onClick = { form.showDisconnect = false }) { Text(stringResource(android.R.string.cancel)) }
                TextButton(enabled = !busy, onClick = {
                    onDisconnect()
                    form.showDisconnect = false
                }) {
                    Text(stringResource(R.string.qobuz_disconnect))
                }
            },
        ) { Text(stringResource(R.string.qobuz_disconnect_description)) }
    }
}

@Composable
fun QobuzQualityPreference(viewModel: QobuzSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val phase = state.phase
    val busy = phase == SourceScreenState.Loading
    ListPreference(
        title = { Text(stringResource(R.string.qobuz)) },
        description = if (!state.connected) stringResource(R.string.qobuz_quality_locked) else null,
        icon = { Icon(painterResource(if (state.connected) R.drawable.album else R.drawable.lock), null) },
        selectedValue = state.quality,
        values = QobuzQuality.entries.toList(),
        valueText = { quality ->
            stringResource(
                when (quality) {
                    QobuzQuality.Standard -> R.string.qobuz_quality_standard
                    QobuzQuality.Cd -> R.string.qobuz_quality_cd
                    QobuzQuality.Studio -> R.string.qobuz_quality_studio
                    QobuzQuality.Master -> R.string.qobuz_quality_master
                },
            )
        },
        valueDescription = { quality ->
            stringResource(
                when (quality) {
                    QobuzQuality.Standard -> R.string.qobuz_quality_standard_description
                    QobuzQuality.Cd -> R.string.qobuz_quality_cd_description
                    QobuzQuality.Studio -> R.string.qobuz_quality_studio_description
                    QobuzQuality.Master -> R.string.qobuz_quality_master_description
                },
            )
        },
        isEnabled = state.connected && !busy,
        onValueSelected = viewModel::updateQuality,
    )
}

@Composable
private fun QobuzCredentialField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    icon: Int,
    enabled: Boolean,
    secret: Boolean = false,
    visible: Boolean = false,
    onToggleVisibility: () -> Unit = {},
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, maxLines = 1) },
        leadingIcon = { Icon(painterResource(icon), null) },
        trailingIcon = if (secret) {
            {
                IconButton(onClick = onToggleVisibility, enabled = enabled) {
                    Icon(
                        painterResource(if (visible) R.drawable.visibility_off else R.drawable.visibility),
                        stringResource(if (visible) R.string.qobuz_hide_credential else R.string.qobuz_show_credential),
                    )
                }
            }
        } else {
            null
        },
        visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Number),
        singleLine = true,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun qobuzFailureText(failure: QobuzFailure?): String {
    val reason = stringResource(
        when (failure?.reason) {
            QobuzFailureReason.Storage -> R.string.sources_storage_error
            QobuzFailureReason.AppId -> R.string.qobuz_error_app_id
            QobuzFailureReason.Token -> R.string.qobuz_error_token
            QobuzFailureReason.Signature -> R.string.qobuz_error_signature
            QobuzFailureReason.Subscription -> R.string.qobuz_error_subscription
            QobuzFailureReason.Region -> R.string.qobuz_error_region
            QobuzFailureReason.Clock -> R.string.qobuz_error_clock
            QobuzFailureReason.RateLimit -> R.string.qobuz_error_rate_limit
            QobuzFailureReason.Preview -> R.string.qobuz_error_preview
            QobuzFailureReason.Format -> R.string.qobuz_error_format
            QobuzFailureReason.Transport -> R.string.qobuz_error_transport
            QobuzFailureReason.Rejected -> R.string.qobuz_error_rejected
            QobuzFailureReason.Network -> R.string.qobuz_error_network
            null -> R.string.qobuz_operation_failed
        },
    )
    val code = failure?.httpStatus ?: return reason
    val stage = stringResource(
        when (failure.stage) {
            QobuzRequestStage.Account -> R.string.account
            QobuzRequestStage.Search -> R.string.search
            QobuzRequestStage.Stream -> R.string.qobuz_stage_stream
            null -> R.string.qobuz
        },
    )

    return stringResource(R.string.qobuz_failure_detail, reason, stage, code)
}
