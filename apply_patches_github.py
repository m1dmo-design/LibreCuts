#!/usr/bin/env python3
"""Apply the LibreCuts autosave + hardening fixes to a checkout (run from repo root).

Only the app-logic and manifest changes are applied here; the AboutLibraries build-plugin
workaround and the sandbox proxy/signing tweaks are deliberately NOT applied, because GitHub
Actions has normal network access and builds a debug APK.
"""
import os, sys, io

ROOT = os.environ.get("LIBRECUTS_ROOT", ".")
J = os.path.join(ROOT, "app/src/main/java/com/tharunbirla/librecuts")
ACT = os.path.join(J, "VideoEditingActivity.kt")
VM = os.path.join(J, "viewmodels/VideoEditingViewModel.kt")
MAN = os.path.join(ROOT, "app/src/main/AndroidManifest.xml")

fails = []
def read(p): return io.open(p, encoding="utf-8").read()
def write(p, s): io.open(p, "w", encoding="utf-8").write(s)
def repl(path, old, new, label, count=1):
    s = read(path); n = s.count(old)
    if n != count:
        fails.append(f"{label}: expected {count}, found {n}"); return
    write(path, s.replace(old, new))

draft = '''package com.tharunbirla.librecuts.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tharunbirla.librecuts.models.EditRecipe
import com.tharunbirla.librecuts.models.VideoProject
import java.io.File

/**
 * ProjectDraftStore - lightweight, on-device autosave for the editor.
 *
 * The editor holds its whole project in memory. If the OS reclaims the process while the
 * app is backgrounded (screen off, low memory, aggressive OEM ROMs) the in-memory project
 * is lost. This store keeps a continuously-updated draft of each project on internal
 * storage, keyed by the source video URI, so the editor can restore it on reopen.
 *
 * Everything is written to app-private internal storage (filesDir). No data leaves the
 * device, and drafts are invisible to the user (the .lcprj export remains the way to
 * share a project).
 */
object ProjectDraftStore {

    private const val TAG = "ProjectDraftStore"
    private const val DIR = "drafts"
    private const val SUFFIX = ".draft.lcprj"
    private const val INDEX = "last_draft"

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    /** Stable, filesystem-safe key for a source URI (survives process restarts). */
    private fun key(uri: Uri): String {
        val hash = uri.toString().hashCode().toUInt().toString(16)
        val tail = (uri.lastPathSegment ?: "source")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .takeLast(48)
        return "${hash}_$tail"
    }

    private fun draftFile(context: Context, uri: Uri): File =
        File(dir(context), key(uri) + SUFFIX)

    /** Serialize the project and write it atomically. Safe to call from any thread. */
    fun save(context: Context, project: VideoProject, displayName: String) {
        try {
            val recipe = EditRecipe.fromVideoProject(displayName, project)
            val json = ProjectSerializer.serialize(recipe)
            val target = draftFile(context, project.sourceUri)
            val tmp = File(dir(context), target.name + ".tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(target)) {
                target.writeText(json)
                tmp.delete()
            }
            File(dir(context), INDEX).writeText(target.name)
        } catch (e: Exception) {
            Log.w(TAG, "Autosave failed: ${e.message}")
        }
    }

    /** Returns the saved draft for a source URI, or null if there is none. */
    fun load(context: Context, uri: Uri): VideoProject? {
        return try {
            val f = draftFile(context, uri)
            if (!f.exists() || f.length() == 0L) return null
            ProjectSerializer.deserialize(f.readText()).toVideoProject()
        } catch (e: Exception) {
            Log.w(TAG, "Draft load failed: ${e.message}")
            null
        }
    }

    /** Remove the draft for a source URI (used when the user explicitly discards). */
    fun clear(context: Context, uri: Uri) {
        try {
            val f = draftFile(context, uri)
            if (f.exists()) f.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Draft clear failed: ${e.message}")
        }
    }
}
'''
write(os.path.join(J, "utils/ProjectDraftStore.kt"), draft)

repl(ACT,
 "    private var exportJob: Job? = null\n    private val activeRenderJobs = mutableListOf<Job>()",
 "    private var exportJob: Job? = null\n    private val activeRenderJobs = mutableListOf<Job>()\n    private var autoSaveJob: Job? = null",
 "activity.autosaveField")
repl(ACT,
 "    private fun observeViewModelState() {\n        lifecycleScope.launch {\n            viewModel.uiState.collect { uiState ->",
 "    private fun observeViewModelState() {\n        // Autosave: persist the project continuously so work survives process death\n        lifecycleScope.launch {\n            viewModel.project.collect { project ->\n                if (project != null) scheduleAutoSave()\n            }\n        }\n\n        lifecycleScope.launch {\n            viewModel.uiState.collect { uiState ->",
 "activity.autosaveCollector")
repl(ACT,
 "            initializeVideoData()\n            if (projectUri == null) {\n                val displayName = videoUri!!.lastPathSegment ?: \"video\"\n                viewModel.initializeProject(videoUri!!, displayName)\n            }",
 "            initializeVideoData()\n            if (projectUri == null) {\n                val displayName = videoUri!!.lastPathSegment ?: \"video\"\n                videoFileName = displayName\n                // Restore an autosaved draft for this source, if one exists - this is what\n                // makes edits survive the OS reclaiming the process while backgrounded.\n                val draft = com.tharunbirla.librecuts.utils.ProjectDraftStore.load(this, videoUri!!)\n                if (draft != null && draft.operations.isNotEmpty()) {\n                    viewModel.loadProject(draft)\n                    Log.d(TAG, \"Restored autosaved draft (${draft.operations.size} operations)\")\n                } else {\n                    viewModel.initializeProject(videoUri!!, displayName)\n                }\n            }",
 "activity.restoreDraft")
repl(ACT,
 '            .setNeutralButton("Discard & Quit") { _, _ ->\n                finish()\n            }',
 '            .setNeutralButton("Discard & Quit") { _, _ ->\n                videoUri?.let { com.tharunbirla.librecuts.utils.ProjectDraftStore.clear(this, it) }\n                finish()\n            }',
 "activity.discardClear")
repl(ACT, "    private fun commitActiveEditsIfAny() {",
         "    private fun commitActiveEditsIfAny(hideToolbars: Boolean = true) {", "activity.commitSig")
repl(ACT, "            findViewById<View>(R.id.imageEditingToolbar)?.visibility = View.GONE",
         "            if (hideToolbars) findViewById<View>(R.id.imageEditingToolbar)?.visibility = View.GONE",
         "activity.commitImageToolbar")
repl(ACT, "            findViewById<View>(R.id.textEditingToolbar)?.visibility = View.GONE",
         "            if (hideToolbars) findViewById<View>(R.id.textEditingToolbar)?.visibility = View.GONE",
         "activity.commitTextToolbar")
old8 = "        findViewById<android.widget.HorizontalScrollView>(R.id.editingControlsScroll)?.visibility = View.VISIBLE"
new8 = "        if (hideToolbars) findViewById<android.widget.HorizontalScrollView>(R.id.editingControlsScroll)?.visibility = View.VISIBLE"
s = read(ACT); pos = s.index("private fun commitActiveEditsIfAny"); head, tail = s[:pos], s[pos:]
if tail.count(old8) != 1: fails.append("activity.commitScroll")
else: write(ACT, head + tail.replace(old8, new8, 1))
repl(ACT,
 "    override fun onPause() {\n        super.onPause()\n        if (::player.isInitialized) {\n            player.pause()\n        }\n        activeRenderJobs.forEach { it.cancel() }\n        activeRenderJobs.clear()\n        frameExtractionJob?.cancel()\n        previewJob?.cancel()\n    }",
 "    override fun onPause() {\n        super.onPause()\n        if (::player.isInitialized) {\n            player.pause()\n        }\n        activeRenderJobs.forEach { it.cancel() }\n        activeRenderJobs.clear()\n        frameExtractionJob?.cancel()\n        previewJob?.cancel()\n\n        // Autosave: commit any in-progress overlay edit and flush the draft immediately.\n        try {\n            commitActiveEditsIfAny(hideToolbars = false)\n        } catch (e: Exception) {\n            Log.w(TAG, \"Commit on pause failed: ${e.message}\")\n        }\n        autoSaveJob?.cancel()\n        autoSaveNow()\n    }",
 "activity.onPause")
repl(ACT,
 "        previewFile?.delete()\n        super.onDestroy()\n        player.release()\n        ffmpegEngine.cleanup()\n    }",
 "        previewFile?.delete()\n        if (::player.isInitialized) {\n            player.release()\n        }\n        ffmpegEngine.cleanup()\n        super.onDestroy()\n    }",
 "activity.onDestroy")
repl(ACT,
 '    private val colorsList = listOf(\n        "#FFFFFF", "#000000", "#FF3B30", "#FF9500", "#FFCC00",',
 '    private fun scheduleAutoSave() {\n        autoSaveJob?.cancel()\n        autoSaveJob = lifecycleScope.launch {\n            kotlinx.coroutines.delay(1200)\n            autoSaveNow()\n        }\n    }\n\n    private fun autoSaveNow() {\n        val project = viewModel.project.value ?: return\n        try {\n            com.tharunbirla.librecuts.utils.ProjectDraftStore.save(this, project, videoFileName)\n        } catch (e: Exception) {\n            Log.w(TAG, "Autosave failed: ${e.message}")\n        }\n    }\n\n    private val colorsList = listOf(\n        "#FFFFFF", "#000000", "#FF3B30", "#FF9500", "#FFCC00",',
 "activity.helpers")

repl(VM, "import kotlinx.coroutines.flow.asStateFlow",
 "import kotlinx.coroutines.flow.asStateFlow\nimport kotlinx.coroutines.flow.map\nimport kotlinx.coroutines.flow.stateIn\nimport kotlinx.coroutines.flow.SharingStarted",
 "vm.imports")
repl(VM,
 "    val operations: StateFlow<List<EditOperation>>\n        get() = MutableStateFlow(project.value?.operations ?: emptyList()).asStateFlow()",
 "    val operations: StateFlow<List<EditOperation>> =\n        _project.map { it?.operations ?: emptyList() }\n            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())",
 "vm.operationsGetter")

repl(MAN, '        <activity\n            android:name=".VideoEditingActivity"\n            android:exported="true"',
 '        <activity\n            android:name=".VideoEditingActivity"\n            android:exported="false"', "manifest.exported")
repl(MAN, '        android:allowBackup="true"', '        android:allowBackup="false"', "manifest.allowBackup")

for f in ["ScratchTest.kt", "ScratchTest.java"]:
    p = os.path.join(J, f)
    if os.path.exists(p): os.remove(p)

if fails:
    print("FAILURES:"); [print("  -", f) for f in fails]; sys.exit(1)
print("PATCHES_OK")
