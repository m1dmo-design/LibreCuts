#!/usr/bin/env python3
"""Build-config tweaks for the signed release build (run from repo root)."""
import os, sys, io

ROOT = os.environ.get("LIBRECUTS_ROOT", ".")
ABG = os.path.join(ROOT, "app/build.gradle")

fails = []
def read(p): return io.open(p, encoding="utf-8").read()
def write(p, s): io.open(p, "w", encoding="utf-8").write(s)
def repl(old, new, label, count=1):
    s = read(ABG); n = s.count(old)
    if n != count:
        fails.append(f"{label}: expected {count}, found {n}"); return
    write(ABG, s.replace(old, new))

repl(
 "        versionCode project.hasProperty('versionCode') ? project.property('versionCode').toInteger() : 10\n        versionName \"1.0-beta7\"",
 "        versionCode project.hasProperty('versionCode') ? project.property('versionCode').toInteger() : 12\n        versionName \"1.0-beta8-autosave\"",
 "abg.version")

repl(
 "    buildTypes {\n        release {\n            minifyEnabled false\n            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'\n        }\n    }",
 "    signingConfigs {\n        release {\n            def ksPath = project.findProperty(\"RELEASE_STORE_FILE\") ?: System.getenv(\"RELEASE_STORE_FILE\")\n            if (ksPath != null) {\n                storeFile file(ksPath)\n                storePassword project.findProperty(\"RELEASE_STORE_PASSWORD\") ?: System.getenv(\"RELEASE_STORE_PASSWORD\")\n                keyAlias project.findProperty(\"RELEASE_KEY_ALIAS\") ?: System.getenv(\"RELEASE_KEY_ALIAS\")\n                keyPassword project.findProperty(\"RELEASE_KEY_PASSWORD\") ?: System.getenv(\"RELEASE_KEY_PASSWORD\")\n            }\n        }\n    }\n\n    buildTypes {\n        release {\n            minifyEnabled false\n            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'\n            if (project.findProperty(\"RELEASE_STORE_FILE\") != null) {\n                signingConfig signingConfigs.release\n            }\n        }\n    }",
 "abg.signing")

if fails:
    print("FAILURES:"); [print("  -", f) for f in fails]; sys.exit(1)
print("BUILD_TWEAKS_OK")
