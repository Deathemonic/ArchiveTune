package moe.rukamori.archivetune.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.sources.SourceFailure
import moe.rukamori.archivetune.sources.SourceProvider
import moe.rukamori.archivetune.ui.screens.settings.labelResId

@Composable
internal fun sourceFallbackDescription(model: StreamingSourceUiModel): String? = sourceFallbackDescription(model.fallbackProvider, model.fallbackFailure)

@Composable
internal fun sourceFallbackDescription(provider: SourceProvider?, failure: SourceFailure?): String? {
    provider ?: return null
    failure ?: return null
    val message = when (failure) {
        SourceFailure.NoMatch -> R.string.source_no_match
        SourceFailure.Account -> R.string.source_account_error
        SourceFailure.Subscription -> R.string.source_quality_denied
        SourceFailure.Unavailable -> R.string.source_track_unavailable
        SourceFailure.Network -> R.string.source_connection_error
    }

    return if (failure == SourceFailure.NoMatch) {
        stringResource(message)
    } else {
        stringResource(message, stringResource(provider.labelResId()))
    }
}
