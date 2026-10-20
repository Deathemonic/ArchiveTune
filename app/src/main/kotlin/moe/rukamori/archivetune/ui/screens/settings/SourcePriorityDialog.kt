package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.sources.SourceProvider
import moe.rukamori.archivetune.ui.component.ItemOrderDialog

@Composable
fun SourcePriorityDialog(
    initialOrder: List<SourceProvider>,
    onDismiss: () -> Unit,
    onConfirm: (List<SourceProvider>) -> Unit,
) {
    ItemOrderDialog(
        title = stringResource(R.string.source_priority),
        initialOrder = initialOrder,
        itemKey = { it.name },
        itemText = { stringResource(it.labelResId()) },
        pinnedItems = listOf(stringResource(R.string.audio_source_youtube)),
        pinnedItemDescription = stringResource(R.string.sources_youtube_last),
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

internal fun SourceProvider.labelResId(): Int = when (this) {
    SourceProvider.Qobuz -> R.string.qobuz
    SourceProvider.Deezer -> R.string.deezer
    SourceProvider.Tidal -> R.string.tidal
}
