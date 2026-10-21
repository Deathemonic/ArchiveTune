/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.GlassDefaults
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.hazeGlass

@OptIn(ExperimentalHazeApi::class)
@Composable
fun FloatingBarGlassBackground(
    hazeState: HazeState,
    shape: RoundedCornerShape,
    pureBlack: Boolean,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val surfaceColor = if (pureBlack) Color.Black else colorScheme.surfaceContainerHigh
    val isDarkSurface = pureBlack || colorScheme.surface.luminance() < colorScheme.onSurface.luminance()
    val hazeInput = remember(hazeState) { HazeInput.Sources(hazeState) }
    val glassStyle = remember(shape, surfaceColor, isDarkSurface) {
        GlassStyle.regular.then {
            shape(shape)
            backgroundColor(surfaceColor)
            tint(surfaceColor.copy(alpha = 0.7f))
            whitePoint(if (isDarkSurface) -0.22f else GlassDefaults.whitePoint)
            contrast(if (isDarkSurface) 0.08f else GlassDefaults.contrast)
            chromaMultiplier(if (isDarkSurface) 1.1f else GlassDefaults.chromaMultiplier)
            specularIntensity(if (isDarkSurface) 0.38f else GlassDefaults.specularIntensity)
            edgeShadow(if (isDarkSurface) Color.Black.copy(alpha = 0.32f) else GlassDefaults.edgeShadow)
            ambientResponse(if (isDarkSurface) 0.08f else GlassDefaults.ambientResponse)
        }
    }
    Box(
        modifier = modifier.hazeGlass(
            input = hazeInput,
            style = glassStyle,
            performanceMode = HazePerformanceMode.Default,
            expandLayerBounds = true,
        ),
    )
}
