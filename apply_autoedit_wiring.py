#!/usr/bin/env python3
"""Wire the Auto Edit screen into the app (manifest, home layout, MainActivity)."""
import os, sys, io

ROOT = os.environ.get("LIBRECUTS_ROOT", ".")
MAN = os.path.join(ROOT, "app/src/main/AndroidManifest.xml")
LAY = os.path.join(ROOT, "app/src/main/res/layout/activity_main.xml")
MAIN = os.path.join(ROOT, "app/src/main/java/com/tharunbirla/librecuts/MainActivity.kt")

fails = []
def read(p): return io.open(p, encoding="utf-8").read()
def write(p, s): io.open(p, "w", encoding="utf-8").write(s)
def repl(path, old, new, label, count=1):
    s = read(path); n = s.count(old)
    if n != count:
        fails.append(f"{label}: expected {count}, found {n}"); return
    write(path, s.replace(old, new))

# 1) Manifest: register AutoEditActivity (not exported - internal only)
repl(MAN,
 '            android:windowSoftInputMode="adjustResize" />\n',
 '            android:windowSoftInputMode="adjustResize" />\n\n'
 '        <activity\n'
 '            android:name=".AutoEditActivity"\n'
 '            android:exported="false"\n'
 '            android:theme="@style/AppTheme" />\n',
 "manifest.autoedit")

# 2) Home layout: add an "Auto Edit" bento card
card = '''            <!-- Bento Card: Auto Edit -->
            <LinearLayout
                android:id="@+id/btnAutoEdit"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:minHeight="150dp"
                android:layout_marginTop="16dp"
                android:background="@drawable/bg_hero_bento_primary"
                android:clickable="true"
                android:focusable="true"
                android:foreground="?attr/selectableItemBackground"
                android:gravity="center_vertical"
                android:orientation="vertical"
                android:padding="24dp"
                android:elevation="4dp">

                <RelativeLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginBottom="12dp">

                    <View
                        android:layout_width="48dp"
                        android:layout_height="48dp"
                        android:background="@drawable/circle_primary_container" />

                    <ImageView
                        android:layout_width="24dp"
                        android:layout_height="24dp"
                        android:layout_centerVertical="true"
                        android:layout_marginStart="12dp"
                        android:src="@drawable/ic_bolt_24"
                        app:tint="@color/onPrimaryContainer" />
                </RelativeLayout>

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="Auto Edit"
                    android:textColor="@color/textColor"
                    android:textSize="18sp"
                    android:fontFamily="sans-serif-medium" />

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="4dp"
                    android:text="Find highlights, cut dead air and edit to the beat \\u2014 fully offline"
                    android:textColor="@color/iconSecondary"
                    android:textSize="13sp"
                    android:fontFamily="sans-serif" />
            </LinearLayout>

            <!-- App Tagline -->'''
repl(LAY,
 '            </LinearLayout>\n\n            <!-- App Tagline -->',
 '            </LinearLayout>\n\n' + card,
 "layout.autoedit")

# 3) MainActivity: picker launcher + button wiring
launcher = '''    private val autoEditPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                    Log.d("AutoEdit", "persist permission failed: ${e.message}")
                }
                startActivity(Intent(this, AutoEditActivity::class.java).apply {
                    putExtra("VIDEO_URI", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            }
        }

    private val selectFolderLauncher ='''
repl(MAIN, '    private val selectFolderLauncher =', launcher, "main.launcher")

repl(MAIN,
 '        binding.btnOpenProject.setBounceClickListener {\n'
 '            Log.d("ButtonClick", "Launching project selection.")\n'
 '            openProjectLauncher.launch(arrayOf("*/*"))\n'
 '        }\n',
 '        binding.btnOpenProject.setBounceClickListener {\n'
 '            Log.d("ButtonClick", "Launching project selection.")\n'
 '            openProjectLauncher.launch(arrayOf("*/*"))\n'
 '        }\n\n'
 '        binding.btnAutoEdit.setBounceClickListener {\n'
 '            Log.d("ButtonClick", "Launching auto edit.")\n'
 '            autoEditPickerLauncher.launch(arrayOf("video/*"))\n'
 '        }\n',
 "main.button")

if fails:
    print("FAILURES:"); [print("  -", f) for f in fails]; sys.exit(1)
print("AUTOEDIT_WIRING_OK")
