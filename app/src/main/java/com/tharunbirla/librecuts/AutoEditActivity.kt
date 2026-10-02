package com.tharunbirla.librecuts

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tharunbirla.librecuts.models.MediaAnalysis
import com.tharunbirla.librecuts.models.Spot
import com.tharunbirla.librecuts.models.SpotType
import com.tharunbirla.librecuts.models.TimeRange
import com.tharunbirla.librecuts.services.FFmpegRenderEngine
import com.tharunbirla.librecuts.utils.AudioAnalyzer
import com.tharunbirla.librecuts.utils.MediaAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * AutoEditActivity - fully on-device "auto edit" toolkit.
 *
 * Analyses a video (scene cuts, silence, freeze, black) and audio beats using the bundled
 * FFmpeg + [AudioAnalyzer], lets the user bookmark moments (+/- N seconds), then generates
 * a new video that either:
 *   - removes the blank/dead parts ("tighten", e.g. 20 min -> 1 min), or
 *   - keeps only the bookmarked / highlight moments.
 *
 * Everything runs locally. No network, no accounts, no uploads.
 */
class AutoEditActivity : AppCompatActivity() {

    private var videoUri: Uri? = null
    private var analysis: MediaAnalysis? = null
    private val bookmarks = ArrayList<TimeRange>()

    private lateinit var engine: FFmpegRenderEngine
    private lateinit var statusText: TextView
    private lateinit var summaryText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var spotContainer: LinearLayout
    private lateinit var windowLabel: TextView
    private lateinit var windowSeek: SeekBar
    private lateinit var cbTighten: CheckBox
    private lateinit var cbHighlights: CheckBox
    private lateinit var generateBtn: Button

    private var windowSeconds = 3

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        videoUri = intent.getParcelableExtra("VIDEO_URI")
        if (videoUri == null) {
            Toast.makeText(this, "No video supplied", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        engine = FFmpegRenderEngine(this)
        setContentView(buildUi())
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.cleanup()
    }

    // ─────────────────────────────────────────────────────────────── UI

    private fun buildUi(): View {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(20), px(20), px(40))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "Auto Edit"; textSize = 24f
            setTextColor(0xFF1F3A5F.toInt())
            setPadding(0, 0, 0, px(4))
        })
        root.addView(TextView(this).apply {
            text = "On-device. Nothing leaves your phone."
            textSize = 13f; setTextColor(0xFF777777.toInt())
            setPadding(0, 0, 0, px(16))
        })

        fun button(label: String, onTap: () -> Unit): Button =
            Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener { onTap() }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = px(8) }
            }

        root.addView(button("Analyse video") { runAnalysis() })
        root.addView(button("Detect audio beats") { runBeats() })

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(12) }
        }
        root.addView(progress)

        statusText = TextView(this).apply {
            textSize = 13f; setTextColor(0xFF444444.toInt())
            setPadding(0, px(10), 0, px(4))
        }
        root.addView(statusText)

        summaryText = TextView(this).apply {
            textSize = 14f; setTextColor(0xFF222222.toInt())
            setPadding(0, px(4), 0, px(8))
        }
        root.addView(summaryText)

        windowLabel = TextView(this).apply {
            text = "Spot window: ±$windowSeconds s"
            textSize = 13f; setTextColor(0xFF444444.toInt())
            setPadding(0, px(12), 0, 0)
        }
        root.addView(windowLabel)
        windowSeek = SeekBar(this).apply {
            max = 14; this.progress = windowSeconds - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    windowSeconds = value + 1
                    windowLabel.text = "Spot window: ±$windowSeconds s"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        root.addView(windowSeek)

        cbTighten = CheckBox(this).apply {
            text = "Remove silent / blank parts (tighten)"
            setTextColor(0xFF222222.toInt())
            setPadding(0, px(12), 0, 0)
        }
        root.addView(cbTighten)
        cbHighlights = CheckBox(this).apply {
            text = "Keep only bookmarked highlights"
            setTextColor(0xFF222222.toInt())
        }
        root.addView(cbHighlights)

        generateBtn = button("Generate edited video") { generate() }
        root.addView(generateBtn)

        root.addView(TextView(this).apply {
            text = "Detected spots"; textSize = 15f
            setTextColor(0xFF1F3A5F.toInt())
            setPadding(0, px(20), 0, px(6))
        })
        spotContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(spotContainer)

        return scroll
    }

    // ─────────────────────────────────────────────────────────────── analysis

    private fun runAnalysis() {
        val uri = videoUri ?: return
        setBusy(true, "Analysing… (this decodes the whole video once)")
        MediaAnalyzer.analyze(this, uri) { result ->
            runOnUiThread {
                analysis = result
                setBusy(false, "")
                renderSpots()
            }
        }
    }

    private fun runBeats() {
        val uri = videoUri ?: return
        setBusy(true, "Detecting beats…")
        AudioAnalyzer.detectBeats(this, uri, onProgress = {}) { beats ->
            runOnUiThread {
                val base = analysis ?: MediaAnalysis()
                analysis = base.copy(beats = beats)
                setBusy(false, "")
                renderSpots()
            }
        }
    }

    private fun setBusy(busy: Boolean, message: String) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        progress.isIndeterminate = busy
        statusText.text = message
        generateBtn.isEnabled = !busy
    }

    private fun renderSpots() {
        val a = analysis ?: return
        spotContainer.removeAllViews()
        val blankMs = a.blankDurationMs()
        summaryText.text = String.format(
            Locale.US,
            "Duration %s  •  scenes %d  •  silence %d  •  freeze %d  •  black %d  •  beats %d\n" +
                "Blank/dead air: %s of %s (%.0f%%)",
            fmt(a.durationMs), a.scenes.size, a.silences.size, a.freezes.size, a.blacks.size, a.beats.size,
            fmt(blankMs), fmt(a.durationMs),
            if (a.durationMs > 0) blankMs * 100.0 / a.durationMs else 0.0
        )

        val spots = ArrayList<Spot>()
        a.scenes.forEach { spots.add(Spot(SpotType.SCENE, it)) }
        a.silences.forEach { spots.add(Spot(SpotType.SILENCE, it.startMs, it.endMs)) }
        a.freezes.forEach { spots.add(Spot(SpotType.FREEZE, it.startMs, it.endMs)) }
        a.blacks.forEach { spots.add(Spot(SpotType.BLACK, it.startMs, it.endMs)) }
        a.beats.forEach { spots.add(Spot(SpotType.BEAT, it)) }
        spots.sortBy { it.timeMs }

        val max = 250
        for (s in spots.take(max)) spotContainer.addView(spotRow(s))
        if (spots.size > max) {
            spotContainer.addView(TextView(this).apply {
                text = "…and ${spots.size - max} more (showing first $max)"
                textSize = 12f; setTextColor(0xFF888888.toInt()); setPadding(0, 8, 0, 0)
            })
        }
    }

    private fun spotRow(spot: Spot): View {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(4), 0, px(4))
        }
        val label = TextView(this).apply {
            text = if (spot.isRange) "${fmt(spot.timeMs)}–${fmt(spot.endMs)}  ${spot.type}"
                   else "${fmt(spot.timeMs)}  ${spot.type}"
            textSize = 13f; setTextColor(0xFF333333.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val markBtn = Button(this).apply {
            text = "Bookmark"; isAllCaps = false; textSize = 12f
            setOnClickListener {
                val half = windowSeconds * 1000L
                val start = (spot.timeMs - half).coerceAtLeast(0L)
                val end = if (spot.isRange) spot.endMs + half else spot.timeMs + half
                bookmarks.add(TimeRange(start, end))
                text = "✓"
                Toast.makeText(this@AutoEditActivity, "Bookmarked ${fmt(spot.timeMs)}", Toast.LENGTH_SHORT).show()
            }
        }
        row.addView(label); row.addView(markBtn)
        return row
    }

    // ─────────────────────────────────────────────────────────────── generate

    private fun buildKeepRanges(): List<TimeRange> {
        val a = analysis ?: return emptyList()
        val duration = if (a.durationMs > 0) a.durationMs else Long.MAX_VALUE
        return when {
            cbHighlights.isChecked -> {
                val windows = if (bookmarks.isNotEmpty()) bookmarks else
                    a.scenes.map { TimeRange((it - windowSeconds * 1000L).coerceAtLeast(0L), it + windowSeconds * 1000L) }
                MediaAnalyzer.normalizeKeep(windows)
            }
            cbTighten.isChecked -> MediaAnalyzer.normalizeKeep(MediaAnalyzer.invert(a.blanks(), duration))
            else -> {
                // Nothing selected: default to removing blanks.
                MediaAnalyzer.normalizeKeep(MediaAnalyzer.invert(a.blanks(), duration))
            }
        }
    }

    private fun generate() {
        val a = analysis
        if (a == null) { Toast.makeText(this, "Analyse first", Toast.LENGTH_SHORT).show(); return }
        val ranges = buildKeepRanges()
        if (ranges.isEmpty()) { Toast.makeText(this, "Nothing to keep — try bookmarking spots", Toast.LENGTH_SHORT).show(); return }
        val uri = videoUri ?: return

        setBusy(true, "Rendering edited video…")
        val expr = MediaAnalyzer.selectExpression(ranges)
        val outFile = File(cacheDir, "autoedit_${System.currentTimeMillis()}.mp4")
        val input = try { FFmpegKitConfig.getSafParameterForRead(this, uri) } catch (e: Exception) { uri.toString() }

        val base = "-y -i \"$input\" " +
                "-vf \"select='$expr',setpts=N/FRAME_RATE/TB\" " +
                "-c:v libx264 -preset veryfast -crf 23 "
        val withAudio = base + "-af \"aselect='$expr',asetpts=N/SR/TB\" -c:a aac \"${outFile.absolutePath}\""
        val videoOnly = base + "-an \"${outFile.absolutePath}\""

        lifecycleScope.launch {
            var result = withContext(Dispatchers.IO) { engine.executeCommand(withAudio) }
            if (result is FFmpegRenderEngine.RenderResult.Failure) {
                withContext(Dispatchers.IO) { outFile.delete() }
                result = withContext(Dispatchers.IO) { engine.executeCommand(videoOnly) }
            }
            when (result) {
                is FFmpegRenderEngine.RenderResult.Success -> {
                    val saved = withContext(Dispatchers.IO) { saveToGallery(outFile) }
                    setBusy(false, if (saved != null) "Saved to Gallery ✓" else "Rendered, but saving to Gallery failed")
                    if (saved != null) {
                        Toast.makeText(this@AutoEditActivity, "Auto edit saved to Gallery", Toast.LENGTH_LONG).show()
                        MaterialAlertDialogBuilder(this@AutoEditActivity)
                            .setTitle("Done")
                            .setMessage("Edited video saved to your Gallery (Movies/LibreCuts).\n\n" +
                                    "Kept ${ranges.size} segment(s), ${fmt(ranges.sumOf { it.durationMs })} of ${fmt(a.durationMs)}.")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                    outFile.delete()
                }
                is FFmpegRenderEngine.RenderResult.Failure -> {
                    setBusy(false, "Render failed")
                    Toast.makeText(this@AutoEditActivity, "Render failed: ${result.error.take(200)}", Toast.LENGTH_LONG).show()
                }
                else -> setBusy(false, "Cancelled")
            }
        }
    }

    private fun saveToGallery(file: File): Uri? {
        val name = "LibreCuts_Auto_${System.currentTimeMillis()}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/LibreCuts")
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "LibreCuts")
                if (!dir.exists()) dir.mkdirs()
                put(MediaStore.Video.Media.DATA, File(dir, name).absolutePath)
            }
        }
        return try {
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            uri
        } catch (e: Exception) {
            null
        }
    }

    private fun fmt(ms: Long): String {
        val total = ms / 1000
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
    }
}
