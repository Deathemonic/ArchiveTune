package moe.rukamori.archivetune.domain.player

import moe.rukamori.archivetune.modules.ModulePlaybackSource
import org.junit.Assert.assertEquals
import org.junit.Test

class ModulePlaybackIndicatorTest {
    private val qobuz = ModulePlaybackSource("qobuz-id", "Qobuz")
    private val tidal = ModulePlaybackSource("tidal-id", "Tidal")

    @Test
    fun currentPlaybackUsesTheSelectedModulesIdentity() {
        val sources = mapOf("first" to qobuz, "second" to tidal)
        assertEquals(ModulePlaybackIndicator.Module(qobuz), modulePlaybackIndicator(true, "first", sources))
        assertEquals(ModulePlaybackIndicator.Module(tidal), modulePlaybackIndicator(true, "second", sources))
    }

    @Test
    fun anotherTracksSelectionDoesNotReplaceYoutubeFallback() {
        assertEquals(ModulePlaybackIndicator.YouTube,
            modulePlaybackIndicator(true, "fallback", mapOf("previous" to qobuz)))
    }

    @Test
    fun fallbackAfterASelectionIsRemovedReturnsToYoutube() {
        val sources = mapOf("track" to qobuz)
        assertEquals(ModulePlaybackIndicator.YouTube, modulePlaybackIndicator(true, "track", sources - "track"))
    }

    @Test
    fun disablingModulesHidesThePillEvenWithAnExistingSelection() {
        assertEquals(ModulePlaybackIndicator.Hidden, modulePlaybackIndicator(false, "track", mapOf("track" to qobuz)))
    }

    @Test
    fun noEligibleCurrentTrackHidesThePill() {
        assertEquals(ModulePlaybackIndicator.Hidden, modulePlaybackIndicator(true, null, mapOf("track" to qobuz)))
    }
}
