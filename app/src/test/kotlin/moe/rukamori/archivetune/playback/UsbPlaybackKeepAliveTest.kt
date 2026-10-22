package moe.rukamori.archivetune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbPlaybackKeepAliveTest {
    @Test
    fun pauseAndResumeReuseTheTrackPreparedBeforeUsbStreaming() {
        val track = RecordingTrack()
        var creations = 0
        val keepAlive = UsbPlaybackKeepAlive {
            creations++
            track
        }

        keepAlive.prepare(true)
        keepAlive.setPlaying(true)
        keepAlive.setPlaying(false)
        keepAlive.prepare(true)
        keepAlive.setPlaying(true)

        assertEquals(1, creations)
        assertEquals(2, track.plays)
        assertEquals(1, track.pauses)
        assertEquals(0, track.releases)
        assertTrue(keepAlive.isPlaying)
    }

    @Test
    fun normalPlaybackNeverCreatesATrack() {
        var creations = 0
        val keepAlive = UsbPlaybackKeepAlive {
            creations++
            RecordingTrack()
        }

        keepAlive.prepare(false)
        keepAlive.setPlaying(true)
        keepAlive.setPlaying(false)
        keepAlive.release()

        assertEquals(0, creations)
        assertFalse(keepAlive.isPlaying)
    }

    @Test
    fun repeatedPlayerEventsDoNotRestartTheTrack() {
        val track = RecordingTrack()
        val keepAlive = UsbPlaybackKeepAlive { track }

        keepAlive.prepare(true)
        repeat(3) { keepAlive.setPlaying(true) }
        repeat(3) { keepAlive.setPlaying(false) }

        assertEquals(1, track.plays)
        assertEquals(1, track.pauses)
        assertFalse(keepAlive.isPlaying)
    }

    @Test
    fun leavingUsbModeReleasesOnceAndCannotRestartTheReleasedTrack() {
        val track = RecordingTrack()
        val keepAlive = UsbPlaybackKeepAlive { track }

        keepAlive.prepare(true)
        keepAlive.setPlaying(true)
        keepAlive.prepare(false)
        keepAlive.release()
        keepAlive.setPlaying(true)

        assertEquals(1, track.releases)
        assertEquals(1, track.plays)
        assertFalse(keepAlive.isPlaying)
    }

    @Test
    fun startFailureReleasesTheTrackWithoutFailingUsbPlayback() {
        val track = RecordingTrack(failPlay = true)
        val keepAlive = UsbPlaybackKeepAlive { track }

        keepAlive.prepare(true)
        keepAlive.setPlaying(true)
        keepAlive.release()

        assertEquals(1, track.releases)
        assertFalse(keepAlive.isPlaying)
    }

    @Test
    fun unsupportedDeviceDoesNotPreventUsbPlayback() {
        val keepAlive = UsbPlaybackKeepAlive { error("No built-in speaker") }

        keepAlive.prepare(true)
        keepAlive.setPlaying(true)
        keepAlive.release()

        assertFalse(keepAlive.isPlaying)
    }

    private class RecordingTrack(private val failPlay: Boolean = false) : UsbPlaybackKeepAlive.Track {
        var plays = 0
        var pauses = 0
        var releases = 0

        override fun play() {
            if (failPlay) error("AudioTrack start failed")
            plays++
        }

        override fun pause() {
            pauses++
        }

        override fun release() {
            releases++
        }
    }
}
