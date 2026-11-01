#!/usr/bin/env python3
"""מוסיף אפליקציה לקטלוג החנות: מוריד את ה-APK, מחשב SHA-256 וקורא את שם החבילה והגרסה.

שימוש:  python3 store/add_app.py <קישור-ל-APK> "שם להצגה" "תיאור קצר"
דורש aapt2 או aapt מ-Android build-tools לקריאת שם החבילה (או להעביר --pkg ו---versionCode ידנית).
"""
import argparse, hashlib, json, os, re, subprocess, sys, tempfile, urllib.request

ap = argparse.ArgumentParser()
ap.add_argument("url")
ap.add_argument("name")
ap.add_argument("desc", nargs="?", default="")
ap.add_argument("--pkg")
ap.add_argument("--version", default="")
ap.add_argument("--versionCode", type=int, default=0)
a = ap.parse_args()

path = os.path.join(tempfile.mkdtemp(), "app.apk")
urllib.request.urlretrieve(a.url, path)
sha = hashlib.sha256(open(path, "rb").read()).hexdigest()

pkg, ver, code = a.pkg, a.version, a.versionCode
if not pkg:
    for tool in ("aapt2", "aapt"):
        try:
            out = subprocess.check_output([tool, "dump", "badging", path], text=True)
            m = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", out)
            pkg, code, ver = m.group(1), int(m.group(2)), m.group(3)
            break
        except Exception:
            continue
if not pkg:
    sys.exit("לא הצלחתי לקרוא את שם החבילה. העבר --pkg ו---versionCode")

catalog_path = os.path.join(os.path.dirname(__file__), "catalog.json")
data = json.load(open(catalog_path, encoding="utf-8"))
data["apps"] = [x for x in data["apps"] if x["pkg"] != pkg]
data["apps"].append({"pkg": pkg, "name": a.name, "version": ver, "versionCode": code,
                     "url": a.url, "sha256": sha, "desc": a.desc})
json.dump(data, open(catalog_path, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
print("נוסף:", pkg, ver, sha)
