"""Robust job-card sweep: locates UI elements via uiautomator instead of fixed taps."""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe")
SHOTS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "shots")


def sh(*args, timeout=40):
    # errors="replace": some dumpsys output is not valid cp1252 and would otherwise
    # raise UnicodeDecodeError inside subprocess's reader thread.
    return subprocess.run([ADB] + list(args), capture_output=True, text=True,
                          encoding="utf-8", errors="replace",
                          timeout=timeout).stdout or ""


def dump():
    sh("shell", "rm", "-f", "/sdcard/ui.xml")
    for _ in range(3):
        sh("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        xml = sh("shell", "cat", "/sdcard/ui.xml")
        if "<hierarchy" in xml:
            return xml
        time.sleep(1)
    return ""


def nodes(xml):
    out = []
    if not xml.strip().startswith("<"):
        xml = xml[xml.index("<"):]
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return out
    for n in root.iter("node"):
        b = n.get("bounds", "")
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b)
        if not m:
            continue
        x1, y1, x2, y2 = map(int, m.groups())
        out.append({
            "text": n.get("text", ""), "desc": n.get("content-desc", ""),
            "cls": n.get("class", ""), "cx": (x1 + x2) // 2, "cy": (y1 + y2) // 2,
            "y1": y1, "y2": y2,
        })
    return out


def find(ns, needle, cls=None, exact=False):
    for n in ns:
        if exact:
            hit = n["text"].strip() == needle or n["desc"].strip() == needle
        else:
            hit = needle.lower() in f"{n['text']} {n['desc']}".lower()
        if hit and (cls is None or cls in n["cls"]):
            return n
    return None


def tap(x, y):
    sh("shell", "input", "tap", str(x), str(y))


def shot(name):
    sh("shell", "screencap", "-p", "/sdcard/_s.png")
    sh("pull", "/sdcard/_s.png", os.path.join(SHOTS, name + ".png"))


def app_foreground():
    out = sh("shell", "dumpsys activity activities")
    return "com.mitas.ppnam.station2aa/.MainActivity" in out and "topResumedActivity" in out


def ensure_app():
    """Relaunch the app (and log back in) if it is not in the foreground."""
    out = sh("shell", "dumpsys activity activities | grep -m1 topResumedActivity")
    if "com.mitas.ppnam.station2aa" in out:
        return
    sh("shell", "monkey", "-p", "com.mitas.ppnam.station2aa",
       "-c", "android.intent.category.LAUNCHER", "1")
    time.sleep(9)
    ns = nodes(dump())
    if find(ns, "Log In"):
        import drive
        drive.login("Avi", "1234")


def goto_lookup():
    """Get back to the Job Lookup screen from wherever we are."""
    for _ in range(6):
        ensure_app()
        ns = nodes(dump())
        gb = find(ns, "Go Back", exact=True)
        if gb:
            tap(gb["cx"], gb["cy"])
            time.sleep(3)
            continue
        if find(ns, "Production Order"):
            return True
        back = find(ns, "Back") or None
        if find(ns, "Scan Ingredients"):
            tap(85, 168)          # top-bar back arrow
            time.sleep(2)
            continue
        # unknown screen - press hardware back
        sh("shell", "input", "keyevent", "4")
        time.sleep(2)
    return False


def lookup(job):
    ns = nodes(dump())
    fld = None
    for n in ns:
        if "EditText" in n["cls"]:
            fld = n
            break
    if fld is None:
        return "NO_FIELD"
    tap(fld["cx"], fld["cy"])
    time.sleep(1.0)
    # clear via select-all + delete
    sh("shell", "input", "keycombination", "113", "29")
    time.sleep(0.4)
    sh("shell", "input", "keyevent", "67")
    time.sleep(0.3)
    for _ in range(14):
        sh("shell", "input", "keyevent", "67")
    sh("shell", "input", "text", job)
    time.sleep(0.6)
    sh("shell", "input", "keyevent", "66")
    return "SUBMITTED"


jobs = sys.argv[1:] if __name__ == "__main__" else []
for j in jobs:
    if not goto_lookup():
        print(f"{j}: COULD_NOT_REACH_LOOKUP", flush=True)
        continue
    r = lookup(j)
    time.sleep(13)
    shot(f"sweep-{j}")
    print(f"{j}: {r}", flush=True)
if __name__ == "__main__":
    print("SWEEP2 DONE", flush=True)
