package moe.rukamori.archivetune.ui.player

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.viewmodels.StreamingSourceViewModel

@Composable
fun StreamingSourcePill(
    modifier: Modifier = Modifier,
    foreground: Color = MaterialTheme.colorScheme.onSurface,
    compact: Boolean = true,
    viewModel: StreamingSourceViewModel = hiltViewModel(key = "streaming-source-pill"),
) {
    val connection = LocalPlayerConnection.current ?: return
    DisposableEffect(connection, viewModel) {
        viewModel.bind(connection)
        onDispose { viewModel.unbind(connection) }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val model = state.model ?: return
    model.title ?: return
    val source = stringResource(model.source.labelRes)

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = foreground.copy(alpha = 0.10f),
            contentColor = foreground,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 7.dp),
                modifier = Modifier.padding(
                    horizontal = if (compact) 7.dp else 12.dp,
                    vertical = if (compact) 2.dp else 6.dp,
                ),
            ) {
                Icon(
                    painterResource(model.source.iconRes),
                    contentDescription = source,
                    modifier = Modifier.size(if (compact) 13.dp else 18.dp),
                )
                Text(
                    text = source,
                    style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f, fill = false).basicMarquee(),
                    maxLines = 1,
                )
            }
        }
    }
}
