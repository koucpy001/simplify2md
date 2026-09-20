package io.github.koucpy001.simplify2md.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PendingRegistryTest {

    @Test
    fun trackThenSettleRoundTrip() {
        val registry = PendingRegistry()
        assertEquals(TrackOutcome.TRACKED, registry.track("a"))
        assertTrue(registry.contains("a"))
        assertEquals(1, registry.size)
        assertTrue(registry.settle("a"))
        assertFalse(registry.contains("a"))
        assertEquals(0, registry.size)
    }

    @Test
    fun duplicateIdDoesNotOverwriteTheFirstEntry() {
        val registry = PendingRegistry()
        assertEquals(TrackOutcome.TRACKED, registry.track("a"))
        assertEquals(TrackOutcome.DUPLICATE, registry.track("a"))
        assertEquals(1, registry.size)
    }

    @Test
    fun settlingAnUnknownIdReturnsFalse() {
        val registry = PendingRegistry()
        assertFalse(registry.settle("missing"))
        assertEquals(0, registry.size)
    }

    @Test
    fun clearReturnsEveryIdAndLaterSettleIsStale() {
        val registry = PendingRegistry()
        registry.track("a")
        registry.track("b")
        assertEquals(setOf("a", "b"), registry.clear().toSet())
        assertEquals(0, registry.size)
        assertFalse(registry.settle("a"))
    }

    @Test
    fun capacityIsBounded() {
        val registry = PendingRegistry(capacity = 2)
        assertEquals(TrackOutcome.TRACKED, registry.track("a"))
        assertEquals(TrackOutcome.TRACKED, registry.track("b"))
        assertEquals(TrackOutcome.CAPACITY_EXCEEDED, registry.track("c"))
        assertFalse(registry.contains("c"))
    }

    @Test
    fun blankRequestIdIsRejected() {
        assertEquals(TrackOutcome.DUPLICATE, PendingRegistry().track(""))
    }

    @Test
    fun concurrentSameIdTracksExactlyOnce() {
        val registry = PendingRegistry()
        val threads = 16
        val start = CountDownLatch(1)
        val outcomes = Collections.synchronizedList(mutableListOf<TrackOutcome>())
        val pool = Executors.newFixedThreadPool(threads)
        repeat(threads) {
            pool.execute {
                start.await()
                outcomes.add(registry.track("same"))
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(1, outcomes.count { it == TrackOutcome.TRACKED })
        assertEquals(threads - 1, outcomes.count { it == TrackOutcome.DUPLICATE })
        assertEquals(1, registry.size)
    }
}
