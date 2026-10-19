#!/usr/bin/env bash
# מחיל את השינויים שלנו על הקוד של פיירפוקס:
#  1. תוסף סינון התמונות המובנה (extension/)
#  2. קוד Kotlin שמחבר את התוסף לאפליקציית המסנן (WlfFilter.kt)
#  3. התקנת התוסף כשמנוע הדפדפן עולה (ליד WebCompatFeature)
#  4. הרשאה לפיירפוקס "לראות" את אפליקציית המסנן (Android 11+)
set -euo pipefail
FF="$1"
HERE="$(cd "$(dirname "$0")" && pwd)"
FENIX="$FF/mobile/android/fenix/app/src/main"

echo "== extension"
mkdir -p "$FENIX/assets/extensions/wlf"
cp "$HERE"/extension/* "$FENIX/assets/extensions/wlf/"

echo "== WlfFilter.kt"
mkdir -p "$FENIX/java/org/mozilla/fenix/wlf"
cp "$HERE/WlfFilter.kt" "$FENIX/java/org/mozilla/fenix/wlf/WlfFilter.kt"

python3 - "$FENIX" <<'PY'
import sys, re
fenix = sys.argv[1]

# התקנת התוסף כשהמנוע נוצר
p = f"{fenix}/java/org/mozilla/fenix/components/Core.kt"
s = open(p).read()
old = "WebCompatFeature.install(it)"
assert old in s, "WebCompatFeature.install not found in Core.kt"
if "WlfFilter.install" not in s:
    s = s.replace(old, old + "\n                org.mozilla.fenix.wlf.WlfFilter.install(context, it)", 1)
open(p, "w").write(s)
print("Core.kt patched")

# Android 11+: כדי להתחבר לשירות של אפליקציה אחרת צריך להצהיר עליה
p = f"{fenix}/AndroidManifest.xml"
s = open(p).read()
if "com.meni.whitelistfilter" not in s:
    m = re.search(r"\n(\s*)<application", s)
    assert m, "<application> not found in manifest"
    q = f"\n{m.group(1)}<queries>\n{m.group(1)}    <package android:name=\"com.meni.whitelistfilter\" />\n{m.group(1)}</queries>\n"
    s = s[:m.start()] + q + s[m.start():]
open(p, "w").write(s)
print("AndroidManifest.xml patched")
PY

echo "done"
