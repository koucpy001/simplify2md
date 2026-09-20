package io.github.koucpy001.simplify2md.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ReadyEventQueueTest {

    private fun event(name: String) = BridgeEvent(name, "null")

    @Test
    fun eventsAreQueuedWhileNotReady() {
        val queue = ReadyEventQueue()
        assertFalse(queue.isReady)
        assertTrue(queue.offer(event("a")))
        assertEquals(1, queue.queuedCount)
    }

    @Test
    fun eventsDispatchImmediatelyOnceReady() {
        val queue = ReadyEventQueue()
        queue.markReady()
        assertTrue(queue.isReady)
        assertFalse(queue.offer(event("a")))
        assertEquals(0, queue.queuedCount)
    }

    @Test
    fun markReadyDrainsInArrivalOrderAndIsIdempotent() {
        val queue = ReadyEventQueue()
        queue.offer(event("first"))
        queue.offer(event("second"))
        assertEquals(listOf("first", "second"), queue.markReady().map { it.name })
        assertEquals(emptyList<BridgeEvent>(), queue.markReady())
    }

    @Test
    fun resetKeepsUndispatchedEventsForTheNextReady() {
        val queue = ReadyEventQueue()
        queue.offer(event("kept"))
        queue.reset()
        assertFalse(queue.isReady)
        assertEquals(listOf("kept"), queue.markReady().map { it.name })
    }

    @Test
    fun resetDoesNotReplayAlreadyDispatchedEvents() {
        val queue = ReadyEventQueue()
        queue.offer(event("dispatched"))
        queue.markReady()
        queue.reset()
        queue.offer(event("afterReset"))
        assertEquals(listOf("afterReset"), queue.markReady().map { it.name })
    }

    @Test
    fun concurrentOffersBeforeReadyAreDrainedExactlyOnce() {
        val queue = ReadyEventQueue()
        val threads = 8
        val perThread = 50
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        repeat(threads) { t ->
            pool.execute {
                start.await()
                repeat(perThread) { i -> queue.offer(event("t$t-$i")) }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(threads * perThread, queue.queuedCount)
        val drained = queue.markReady()
        assertEquals(threads * perThread, drained.size)
        assertEquals(drained.size, drained.map { it.name }.toSet().size)
        assertEquals(0, queue.queuedCount)
    }
}
