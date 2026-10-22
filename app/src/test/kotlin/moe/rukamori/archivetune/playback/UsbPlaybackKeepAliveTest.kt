package moe.rukamori.archivetune.playback

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.domain.player.UsbPlaybackProtectionFailure
import moe.rukamori.archivetune.domain.player.UsbPlaybackProtectionState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbPlaybackKeepAliveTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val keepAlives = mutableListOf<UsbPlaybackKeepAlive>()

    @After
    fun tearDown() = runBlocking {
        withTimeout(5_000) { keepAlives.map { it.close() }.joinAll() }
        scope.cancel()
    }

    @Test
    fun pauseAndResumeReuseTheTrackPreparedBeforeUsbStreaming() {
        val track = RecordingTrack()
        var creations = 0
        val keepAlive = keeper { creations++; track }

        keepAlive.prepare(true).await()
        keepAlive.setPlaying(true).await()
        keepAlive.setPlaying(false).await()
        keepAlive.prepare(true).await()
        keepAlive.setPlaying(true).await()

        assertEquals(1, creations)
        assertEquals(2, track.plays)
        assertEquals(1, track.pauses)
        assertEquals(0, track.releases)
        assertTrue(keepAlive.isPlaying)
    }

    @Test
    fun normalPlaybackNeverCreatesATrack() {
        var creations = 0
        val keepAlive = keeper { creations++; RecordingTrack() }

        keepAlive.prepare(false).await()
        keepAlive.setPlaying(true).await()
        keepAlive.setPlaying(false).await()

        assertEquals(0, creations)
        assertEquals(UsbPlaybackProtectionState.Disabled, keepAlive.state.value)
    }

    @Test
    fun repeatedPlayerEventsDoNotRestartTheTrack() {
        val track = RecordingTrack()
        val keepAlive = keeper { track }

        keepAlive.prepare(true).await()
        repeat(3) { keepAlive.setPlaying(true).await() }
        repeat(3) { keepAlive.setPlaying(false).await() }

        assertEquals(1, track.plays)
        assertEquals(1, track.pauses)
        assertFalse(keepAlive.isPlaying)
    }

    @Test
    fun preparingAnExistingPlayingTrackDoesNotLoseTheNextPause() {
        val track = RecordingTrack()
        val keepAlive = keeper { track }

        keepAlive.prepare(true).await()
        keepAlive.setPlaying(true).await()
        keepAlive.prepare(true).await()
        keepAlive.setPlaying(false).await()

        assertEquals(1, track.pauses)
        assertFalse(keepAlive.isPlaying)
    }

    @Test
    fun leavingUsbModeReleasesOnceAndCannotRestartTheReleasedTrack() {
        val track = RecordingTrack()
        val keepAlive = keeper { track }

        keepAlive.prepare(true).await()
        keepAlive.setPlaying(true).await()
        keepAlive.prepare(false).await()
        runBlocking { keepAlive.close().join() }

        assertTrue(keepAlive.setPlaying(true).isCancelled)
        assertEquals(1, track.releases)
        assertEquals(1, track.plays)
        assertFalse(keepAlive.isPlaying)
    }

    @Test
    fun startFailureIsReportedAndReleasesTheTrack() {
        val track = RecordingTrack(failPlay = true)
        val keepAlive = keeper { track }

        keepAlive.prepare(true).await()
        val result = keepAlive.setPlaying(true).await()

        assertEquals(UsbPlaybackProtectionState.Error(UsbPlaybackProtectionFailure.Playback), result)
        assertEquals(result, keepAlive.state.value)
        assertEquals(1, track.releases)
        assertFalse(keepAlive.isPlaying)
    }

    @Test
    fun unsupportedDeviceIsReportedWithoutFailingUsbPlayback() {
        val keepAlive = keeper { error("No built-in speaker") }

        val result = keepAlive.prepare(true).await()
        keepAlive.awaitPreparedOnPlaybackThread()

        assertEquals(UsbPlaybackProtectionState.Error(UsbPlaybackProtectionFailure.Setup), result)
        assertEquals(result, keepAlive.state.value)
    }

    @Test
    fun releaseFailureIsReported() {
        val keepAlive = keeper { RecordingTrack(failRelease = true) }
        keepAlive.prepare(true).await()

        val result = keepAlive.prepare(false).await()

        assertEquals(UsbPlaybackProtectionState.Error(UsbPlaybackProtectionFailure.Release), result)
        assertEquals(result, keepAlive.state.value)
    }

    @Test
    fun allAudioOperationsRunOffTheCallingThread() {
        val caller = Thread.currentThread()
        val threads = CopyOnWriteArraySet<Thread>()
        val keepAlive = keeper {
            threads.add(Thread.currentThread())
            RecordingTrack(threads = threads)
        }

        keepAlive.prepare(true).await()
        keepAlive.setPlaying(true).await()
        keepAlive.setPlaying(false).await()
        keepAlive.prepare(false).await()

        assertTrue(threads.isNotEmpty())
        assertFalse(threads.contains(caller))
    }

    @Test
    fun setupReturnsImmediatelyAndThePlaybackBarrierWaitsForIt() {
        val started = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val keepAlive = keeper {
            started.countDown()
            check(proceed.await(5, TimeUnit.SECONDS))
            RecordingTrack()
        }
        try {
            val prepared = keepAlive.prepare(true)
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertFalse(prepared.isDone)
            val enteredBarrier = CountDownLatch(1)
            val opened = CompletableFuture.supplyAsync {
                enteredBarrier.countDown()
                keepAlive.awaitPreparedOnPlaybackThread()
                true
            }
            assertTrue(enteredBarrier.await(5, TimeUnit.SECONDS))
            assertFalse(opened.isDone)

            proceed.countDown()

            assertEquals(UsbPlaybackProtectionState.Ready, prepared.await())
            assertTrue(opened.get(5, TimeUnit.SECONDS))
        } finally {
            proceed.countDown()
        }
    }

    @Test
    fun playbackBarrierFollowsTheLatestModeChange() {
        val firstStarted = CountDownLatch(1)
        val firstReady = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val secondReady = CountDownLatch(1)
        val creations = AtomicInteger()
        val keepAlive = keeper {
            if (creations.incrementAndGet() == 1) {
                firstStarted.countDown()
                check(firstReady.await(5, TimeUnit.SECONDS))
            } else {
                secondStarted.countDown()
                check(secondReady.await(5, TimeUnit.SECONDS))
            }
            RecordingTrack()
        }
        try {
            keepAlive.prepare(true)
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
            val enteredBarrier = CountDownLatch(1)
            val opened = CompletableFuture.supplyAsync {
                enteredBarrier.countDown()
                keepAlive.awaitPreparedOnPlaybackThread()
                true
            }
            assertTrue(enteredBarrier.await(5, TimeUnit.SECONDS))
            keepAlive.prepare(false)
            keepAlive.prepare(true)
            firstReady.countDown()
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            assertFalse(opened.isDone)

            secondReady.countDown()

            assertTrue(opened.get(5, TimeUnit.SECONDS))
        } finally {
            firstReady.countDown()
            secondReady.countDown()
        }
    }

    @Test
    fun closingDuringSetupReleasesTheLateTrack() {
        val started = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val track = RecordingTrack()
        val keepAlive = keeper {
            started.countDown()
            check(proceed.await(5, TimeUnit.SECONDS))
            track
        }
        try {
            keepAlive.prepare(true)
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val closed = keepAlive.close()
            proceed.countDown()
            runBlocking { withTimeout(5_000) { closed.join() } }

            assertEquals(1, track.releases)
            assertEquals(UsbPlaybackProtectionState.Disabled, keepAlive.state.value)
        } finally {
            proceed.countDown()
        }
    }

    @Test
    fun scopeCancellationReleasesTheTrackAndRejectsFurtherSetup() {
        val track = RecordingTrack()
        val keepAlive = keeper { track }
        keepAlive.prepare(true).await()

        scope.cancel()
        runBlocking { withTimeout(5_000) { keepAlive.close().join() } }

        assertEquals(1, track.releases)
        assertTrue(keepAlive.prepare(true).isCancelled)
        assertEquals(UsbPlaybackProtectionState.Disabled, keepAlive.state.value)
    }

    @Test
    fun cancellationDuringSetupCancelsTheResultAndReleasesTheLateTrack() {
        val started = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val track = RecordingTrack()
        val keepAlive = keeper {
            started.countDown()
            check(proceed.await(5, TimeUnit.SECONDS))
            track
        }
        try {
            val prepared = keepAlive.prepare(true)
            assertTrue(started.await(5, TimeUnit.SECONDS))
            scope.cancel()
            proceed.countDown()
            runBlocking { withTimeout(5_000) { keepAlive.close().join() } }

            assertTrue(prepared.isCancelled)
            assertEquals(1, track.releases)
            assertEquals(UsbPlaybackProtectionState.Disabled, keepAlive.state.value)
        } finally {
            proceed.countDown()
        }
    }

    @Test
    fun cancellationIsNotConvertedIntoASetupFailure() {
        val keepAlive = keeper { throw CancellationException("Service stopped") }
        val preparation = keepAlive.prepare(true)
        try {
            preparation.await()
            error("Expected cancellation")
        } catch (_: java.util.concurrent.CancellationException) {
            runBlocking { withTimeout(5_000) { keepAlive.close().join() } }
            assertEquals(UsbPlaybackProtectionState.Disabled, keepAlive.state.value)
        }
    }

    private fun keeper(create: () -> UsbPlaybackKeepAlive.Track) = UsbPlaybackKeepAlive(scope, create).also(keepAlives::add)

    private fun CompletableFuture<UsbPlaybackProtectionState>.await() = get(5, TimeUnit.SECONDS)

    private class RecordingTrack(
        private val failPlay: Boolean = false,
        private val failRelease: Boolean = false,
        private val threads: MutableSet<Thread> = CopyOnWriteArraySet(),
    ) : UsbPlaybackKeepAlive.Track {
        var plays = 0
        var pauses = 0
        var releases = 0

        override fun play() {
            threads.add(Thread.currentThread())
            if (failPlay) error("AudioTrack start failed")
            plays++
        }

        override fun pause() {
            threads.add(Thread.currentThread())
            pauses++
        }

        override fun release() {
            threads.add(Thread.currentThread())
            releases++
            if (failRelease) error("AudioTrack release failed")
        }
    }
}
