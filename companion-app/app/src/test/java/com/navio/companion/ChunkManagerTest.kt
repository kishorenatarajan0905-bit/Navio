package com.navio.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkManagerTest {

    @Test
    fun `windowFor clamps at start and end`() {
        assertEquals(Pair(0, 46), ChunkManager.windowFor(0, 100))
        assertEquals(Pair(0, 46), ChunkManager.windowFor(3, 100))
        assertEquals(Pair(5, 51), ChunkManager.windowFor(10, 100))
        assertEquals(Pair(93, 100), ChunkManager.windowFor(98, 100))
        assertEquals(Pair(0, 20), ChunkManager.windowFor(0, 20))
    }

    @Test
    fun `needsNewChunk triggers when approaching chunk end`() {
        ChunkManager.reset()
        ChunkManager.applyWindow(0, 46)
        assertFalse(ChunkManager.needsNewChunk(0, 413))
        assertFalse(ChunkManager.needsNewChunk(29, 413))
        assertTrue(ChunkManager.needsNewChunk(30, 413))
    }

    @Test
    fun `needsNewChunk triggers when rider falls behind`() {
        ChunkManager.reset()
        ChunkManager.applyWindow(30, 76)
        assertTrue(ChunkManager.needsNewChunk(2, 413))
        assertFalse(ChunkManager.needsNewChunk(26, 413))
    }

    @Test
    fun `final chunk guard stops re-sends at end of route`() {
        ChunkManager.reset()
        ChunkManager.applyWindow(69, 90)
        assertFalse(ChunkManager.needsNewChunk(89, 90))
        assertFalse(ChunkManager.needsNewChunk(80, 90))
    }

    @Test
    fun `sliding window covers full route`() {
        val total = 413
        ChunkManager.reset()
        val (s0, e0) = ChunkManager.windowFor(0, total)
        ChunkManager.applyWindow(s0, e0)

        var chunks = 0
        var maxChunk = 0
        var rider = 0
        while (rider < total) {
            if (ChunkManager.needsNewChunk(rider, total)) {
                val (start, end) = ChunkManager.windowFor(rider, total)
                if (ChunkManager.windowChanged(start, end)) {
                    ChunkManager.applyWindow(start, end)
                    chunks++
                    maxChunk = maxOf(maxChunk, end - start)
                }
            }
            assertTrue(
                "rider $rider outside window [${ChunkManager.chunkStartIdx}, ${ChunkManager.chunkEndIdx})",
                rider >= ChunkManager.chunkStartIdx && rider < ChunkManager.chunkEndIdx
            )
            rider++
        }
        assertTrue("expected multiple chunks, got $chunks", chunks > 5)
        assertTrue("chunk too big: $maxChunk", maxChunk <= ChunkManager.CHUNK_SIZE)
        assertEquals(total, ChunkManager.chunkEndIdx)
    }
}
