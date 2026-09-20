package io.github.koucpy001.simplify2md.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PickerSlotTest {

    @Test
    fun exactlyOnePickerAtATime() {
        val slot = PickerSlot()
        assertTrue(slot.tryAcquire())
        assertFalse(slot.tryAcquire())
        assertTrue(slot.isPending)
        slot.release()
        assertFalse(slot.isPending)
        assertTrue(slot.tryAcquire())
    }

    @Test
    fun concurrentAcquisitionHasASingleWinner() {
        val slot = PickerSlot()
        val threads = 16
        val start = CountDownLatch(1)
        val wins = AtomicInteger()
        val pool = Executors.newFixedThreadPool(threads)
        repeat(threads) {
            pool.execute {
                start.await()
                if (slot.tryAcquire()) wins.incrementAndGet()
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(1, wins.get())
    }

    @Test
    fun treeAuthIsPerDocumentAndIndependentOfThePickerSlot() {
        val slot = PickerSlot()
        val dedup = TreeAuthDedup()
        val uri = "content://tree/a"

        assertTrue(slot.tryAcquire())
        assertTrue(dedup.markIfFirst(uri))
        assertFalse(dedup.markIfFirst(uri))
        assertTrue(dedup.markIfFirst("content://tree/b"))
        assertTrue(dedup.isPrompted(uri))

        dedup.forget(uri)
        assertTrue(dedup.markIfFirst(uri))

        dedup.clear()
        assertFalse(dedup.isPrompted(uri))
    }
}
