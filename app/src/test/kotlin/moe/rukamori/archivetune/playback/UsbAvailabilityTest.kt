package moe.rukamori.archivetune.playback

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.domain.player.UsbAvailability
import org.junit.Assert.assertEquals
import org.junit.Test

class UsbAvailabilityTest {
    @Test
    fun registrationFailureBecomesAnExplicitError() = runBlocking {
        val state = observeUsbAvailability(
            register = { throw SecurityException("Receiver registration denied") },
            hasOutput = { true },
        ).single()

        assertEquals(UsbAvailability.Error, state)
    }

    @Test
    fun queryFailureCanRecoverAfterAnotherUsbEventAndCancellationUnregisters() = runBlocking {
        val fail = AtomicBoolean(true)
        val closed = AtomicInteger()
        val changed = AtomicReference<() -> Unit>()
        val events = Channel<UsbAvailability>(Channel.UNLIMITED)
        val flow = observeUsbAvailability(
            register = { callback ->
                changed.set(callback)
                AutoCloseable { closed.incrementAndGet() }
            },
            hasOutput = {
                if (fail.get()) throw IllegalStateException("USB service unavailable")
                true
            },
        )
        val collector = launch { flow.collect { events.send(it) } }
        try {
            assertEquals(UsbAvailability.Error, withTimeout(5_000) { events.receive() })
            fail.set(false)
            changed.get().invoke()
            assertEquals(UsbAvailability.Available, withTimeout(5_000) { events.receive() })
        } finally {
            collector.cancelAndJoin()
        }
        assertEquals(1, closed.get())
    }

    @Test
    fun absenceIsReportedSeparatelyFromAnError() = runBlocking {
        val closed = AtomicInteger()
        val flow = observeUsbAvailability(
            register = { AutoCloseable { closed.incrementAndGet() } },
            hasOutput = { false },
        )
        val events = Channel<UsbAvailability>(Channel.UNLIMITED)
        val collector = launch { flow.collect { events.send(it) } }
        try {
            assertEquals(UsbAvailability.Unavailable, withTimeout(5_000) { events.receive() })
        } finally {
            collector.cancelAndJoin()
        }
        assertEquals(1, closed.get())
    }
}
