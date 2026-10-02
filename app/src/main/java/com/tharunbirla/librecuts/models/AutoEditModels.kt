package com.tharunbirla.librecuts.models

/** A simple [startMs, endMs) interval on the timeline. */
data class TimeRange(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
    fun overlaps(other: TimeRange): Boolean = startMs < other.endMs && other.startMs < endMs
}

/** What kind of moment a detected spot is. */
enum class SpotType { SCENE, SILENCE, FREEZE, BLACK, BEAT, BOOKMARK }

/**
 * A single detected moment in the media.
 *  - SCENE   : a visual cut / scene change (timeMs = the change)
 *  - SILENCE : a silent audio stretch (timeMs..endMs)
 *  - FREEZE  : a frozen / static video stretch (timeMs..endMs)
 *  - BLACK   : a black stretch (timeMs..endMs)
 *  - BEAT    : an audio beat (timeMs = the beat)
 *  - BOOKMARK: a spot the user bookmarked
 */
data class Spot(
    val type: SpotType,
    val timeMs: Long,
    val endMs: Long = timeMs,
    val score: Float = 0f
) {
    val isRange: Boolean get() = endMs > timeMs
}

/** The full result of analysing a media file. All times in milliseconds. */
data class MediaAnalysis(
    val durationMs: Long = 0L,
    val silences: List<TimeRange> = emptyList(),
    val freezes: List<TimeRange> = emptyList(),
    val blacks: List<TimeRange> = emptyList(),
    val scenes: List<Long> = emptyList(),
    val beats: List<Long> = emptyList()
) {
    /** All "blank" (non-content) stretches, merged and sorted. */
    fun blanks(): List<TimeRange> = merge(silences + freezes + blacks)

    /** Total blank time. */
    fun blankDurationMs(): Long = blanks().sumOf { it.durationMs }

    companion object {
        /** Merge overlapping / near-adjacent ranges (gap tolerance 60 ms). */
        fun merge(ranges: List<TimeRange>): List<TimeRange> {
            val sorted = ranges.filter { it.endMs >= it.startMs }.sortedBy { it.startMs }
            if (sorted.isEmpty()) return emptyList()
            val out = ArrayList<TimeRange>()
            var cur = sorted[0]
            for (r in sorted.drop(1)) {
                if (r.startMs <= cur.endMs + 60) {
                    cur = TimeRange(cur.startMs, maxOf(cur.endMs, r.endMs))
                } else {
                    out.add(cur); cur = r
                }
            }
            out.add(cur)
            return out
        }
    }
}
