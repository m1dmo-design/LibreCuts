package com.tharunbirla.librecuts.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.antonkarpenko.ffmpegkit.FFmpegSession
import com.antonkarpenko.ffmpegkit.Log
import com.antonkarpenko.ffmpegkit.Statistics
import com.tharunbirla.librecuts.models.MediaAnalysis
import com.tharunbirla.librecuts.models.TimeRange

/**
 * MediaAnalyzer - fully on-device analysis of a video using FFmpeg filters that are already
 * bundled with the app. No network, no ML models, no third-party services.
 *
 * A single FFmpeg decode pass detects:
 *  - silence     (silencedetect)  -> dead air
 *  - freeze      (freezedetect)   -> static / held frames ("blank")
 *  - black       (blackdetect)    -> black frames ("blank")
 *  - scene cuts  (select+showinfo)-> visual highlights / shot boundaries
 *
 * Beat detection is handled separately by [AudioAnalyzer] (energy-based onset detection).
 */
object MediaAnalyzer {

    private const val TAG = "MediaAnalyzer"

    // Tunables (kept here so they are easy to expose in the UI later).
    private const val SILENCE_NOISE = "-30dB"
    private const val SILENCE_MIN = "0.6"
    private const val FREEZE_NOISE = "-60dB"
    private const val FREEZE_MIN = "1.5"
    private const val BLACK_PIX = "0.10"
    private const val BLACK_MIN = "0.1"
    private const val SCENE_THRESHOLD = "0.35"

    /**
     * Analyse [videoUri]. Runs asynchronously; [onComplete] is invoked on an FFmpeg worker
     * thread (callers should marshal to the main thread before touching UI).
     */
    fun analyze(
        context: Context,
        videoUri: Uri,
        onComplete: (MediaAnalysis) -> Unit
    ) {
        val input = try {
            FFmpegKitConfig.getSafParameterForRead(context, videoUri)
        } catch (e: Exception) {
            videoUri.toString()
        }

        val vf = "blackdetect=d=$BLACK_MIN:pix_th=$BLACK_PIX," +
                "freezedetect=n=$FREEZE_NOISE:d=$FREEZE_MIN," +
                "select='gt(scene,$SCENE_THRESHOLD)',showinfo"
        val af = "silencedetect=noise=$SILENCE_NOISE:d=$SILENCE_MIN"
        val cmd = "-hide_banner -nostats -i \"$input\" -af \"$af\" -vf \"$vf\" -f null -"

        val buffer = StringBuilder()
        try {
            FFmpegKit.executeAsync(
                cmd,
                { _: FFmpegSession ->
                    val analysis = try { parse(buffer.toString()) }
                    catch (e: Exception) { Log.w(TAG, "parse failed: ${e.message}"); MediaAnalysis() }
                    onComplete(analysis)
                },
                { log: Log -> buffer.append(log.message ?: "").append('\n') },
                { _: Statistics -> }
            )
        } catch (e: Exception) {
            Log.e(TAG, "analysis failed to start: ${e.message}", e)
            onComplete(MediaAnalysis())
        }
    }

    /** Parse the concatenated FFmpeg log output into a [MediaAnalysis]. */
    fun parse(text: String): MediaAnalysis {
        val durationMs = Regex("Duration: (\\d+):(\\d+):(\\d+)\\.(\\d+)").find(text)?.let {
            val h = it.groupValues[1].toLong()
            val m = it.groupValues[2].toLong()
            val s = it.groupValues[3].toLong()
            val cs = it.groupValues[4].toLong()
            ((h * 3600 + m * 60 + s) * 1000L) + cs * 10L
        } ?: 0L

        val silences = parseRanges(text, "silence_start: ?([\\d.]+)", "silence_end: ?([\\d.]+)")
        val blacks = parseRanges(text, "black_start:?\\s*([\\d.]+)", "black_end:?\\s*([\\d.]+)")
        val freezes = parseRanges(text, "freeze_start: ?([\\d.]+)", "freeze_end: ?([\\d.]+)")

        val scenes = Regex("pts_time:([\\d.]+)").findAll(text)
            .map { (it.groupValues[1].toDouble() * 1000.0).toLong() }
            .filter { it > 0 }
            .toList()

        return MediaAnalysis(
            durationMs = durationMs,
            silences = silences,
            freezes = freezes,
            blacks = blacks,
            scenes = scenes,
            beats = emptyList()
        )
    }

    private fun parseRanges(text: String, startPat: String, endPat: String): List<TimeRange> {
        val starts = Regex(startPat).findAll(text)
            .map { (it.groupValues[1].toDouble() * 1000.0).toLong() }.toList()
        val ends = Regex(endPat).findAll(text)
            .map { (it.groupValues[1].toDouble() * 1000.0).toLong() }.toList()
        val out = ArrayList<TimeRange>(starts.size)
        for (i in starts.indices) {
            val e = ends.getOrNull(i) ?: starts.getOrNull(i + 1) ?: starts[i]
            out.add(TimeRange(starts[i], maxOf(starts[i], e)))
        }
        return out
    }

    /** The complement of [cut] within [0, durationMs] - i.e. the parts to KEEP. */
    fun invert(cut: List<TimeRange>, durationMs: Long): List<TimeRange> {
        val merged = MediaAnalysis.merge(cut)
        if (merged.isEmpty()) return if (durationMs > 0) listOf(TimeRange(0, durationMs)) else emptyList()
        val keep = ArrayList<TimeRange>()
        var cursor = 0L
        for (r in merged) {
            if (r.startMs > cursor) keep.add(TimeRange(cursor, minOf(r.startMs, durationMs)))
            cursor = maxOf(cursor, r.endMs)
        }
        if (cursor < durationMs) keep.add(TimeRange(cursor, durationMs))
        return keep.filter { it.durationMs > 100 }
    }

    /** Merge a list of keep-ranges and drop anything shorter than [minMs]. */
    fun normalizeKeep(ranges: List<TimeRange>, minMs: Long = 400L): List<TimeRange> =
        MediaAnalysis.merge(ranges).filter { it.durationMs >= minMs }

    /** Cap total kept duration to [maxMs] by dropping the trailing ranges. */
    fun capTotal(ranges: List<TimeRange>, maxMs: Long): List<TimeRange> {
        if (maxMs <= 0) return ranges
        val out = ArrayList<TimeRange>()
        var total = 0L
        for (r in ranges) {
            if (total >= maxMs) break
            val room = maxMs - total
            if (r.durationMs <= room) { out.add(r); total += r.durationMs }
            else { out.add(TimeRange(r.startMs, r.startMs + room)); total = maxMs }
        }
        return out
    }

    /** Build the FFmpeg `select`/`aselect` expression for a list of keep-ranges. */
    fun selectExpression(ranges: List<TimeRange>): String {
        if (ranges.isEmpty()) return "0"
        return ranges.joinToString("+") {
            val s = it.startMs / 1000.0
            val e = it.endMs / 1000.0
            "between(t\\,$s\\,$e)"
        }
    }
}
