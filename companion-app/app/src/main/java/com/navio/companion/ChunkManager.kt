package com.navio.companion

object ChunkManager {

    const val CHUNK_SIZE = 46
    const val OVERLAP_POINTS = 5
    const val TRIGGER_AHEAD = 16

    var chunkStartIdx = 0
        private set
    var chunkEndIdx = 0
        private set

    fun reset() {
        chunkStartIdx = 0
        chunkEndIdx = 0
    }

    fun windowFor(riderIdx: Int, total: Int): Pair<Int, Int> {
        val start = maxOf(0, riderIdx - OVERLAP_POINTS)
        val end = minOf(total, start + CHUNK_SIZE)
        return Pair(start, end)
    }

    fun needsNewChunk(riderIdx: Int, total: Int): Boolean {
        if (total <= 0) return false
        if (chunkEndIdx >= total) return false
        if (riderIdx >= chunkEndIdx - TRIGGER_AHEAD) return true
        if (riderIdx + OVERLAP_POINTS < chunkStartIdx) return true
        return false
    }

    fun windowChanged(start: Int, end: Int): Boolean =
        start != chunkStartIdx || end > chunkEndIdx

    fun applyWindow(start: Int, end: Int) {
        chunkStartIdx = start
        chunkEndIdx = end
    }
}
