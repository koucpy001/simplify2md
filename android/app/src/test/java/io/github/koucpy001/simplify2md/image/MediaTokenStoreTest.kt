package io.github.koucpy001.simplify2md.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Token table backing the `/media/<token>` streaming endpoint (plan todo 13e):
 * bounded LRU, resolve-as-use, and clear on Activity destroy.
 */
class MediaTokenStoreTest {

    @Test
    fun registerAndResolveRoundTrip() {
        val store = MediaTokenStore()
        val token = store.register("content://doc/1", "image/png")
        val entry = store.resolve(token)
        assertEquals("content://doc/1", entry?.uri)
        assertEquals("image/png", entry?.mime)
    }

    @Test
    fun unknownTokenResolvesToNull() {
        assertNull(MediaTokenStore().resolve("nope"))
    }

    @Test
    fun lruEvictsOldestOverCapacity() {
        val store = MediaTokenStore(capacity = 2)
        val a = store.register("content://doc/a", "image/png")
        store.register("content://doc/b", "image/png")
        store.register("content://doc/c", "image/png")
        assertNull("the oldest entry must be evicted", store.resolve(a))
        assertEquals(2, store.size)
    }

    @Test
    fun resolveCountsAsUse() {
        val store = MediaTokenStore(capacity = 2)
        val a = store.register("content://doc/a", "image/png")
        val b = store.register("content://doc/b", "image/png")
        // Using A makes it most-recent; registering C then evicts B.
        store.resolve(a)
        store.register("content://doc/c", "image/png")
        assertNull(store.resolve(b))
        assertEquals("content://doc/a", store.resolve(a)?.uri)
    }

    @Test
    fun clearDropsEveryToken() {
        val store = MediaTokenStore()
        val token = store.register("content://doc/1", "image/png")
        store.clear()
        assertNull(store.resolve(token))
        assertEquals(0, store.size)
    }
}