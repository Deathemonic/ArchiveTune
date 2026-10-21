/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import moe.rukamori.archivetune.innertube.pages.QueueChip

data class QueueRecommendations(
    val chips: List<QueueChip> = emptyList(),
    val loading: Boolean = false,
    val pendingChipId: String? = null,
    val failed: Boolean = false,
)
