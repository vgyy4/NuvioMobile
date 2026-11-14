#!/usr/bin/env python3
"""Drives the Nuvio app in an iOS simulator with idb (real taps and swipes) and records
screenshots and a video of the navigation bar, scrolling and long-press scenes.

Usage: drive.py <udid> <bundle id> <output dir>
Everything it learns is written to the output dir (dumps of the accessibility tree and a log), so a
failed run can still be diagnosed from the artifact.
"""
import json
import os
import re
import subprocess
import sys
import time

udid, bundle, out = sys.argv[1], sys.argv[2], sys.argv[3]
os.makedirs(out, exist_ok=True)
LOG = open(os.path.join(out, "driver.log"), "a", buffering=1)


def log(*parts):
    line = " ".join(str(p) for p in parts)
    print(line, flush=True)
    LOG.write(line + "\n")


def run(cmd, check=False, timeout=120, capture=True):
    log("$", " ".join(cmd))
    proc = subprocess.run(cmd, capture_output=capture, text=True, timeout=timeout)
    if proc.stdout and capture:
        log(proc.stdout.strip()[:2000])
    if proc.returncode != 0:
        log("exit", proc.returncode, (proc.stderr or "").strip()[:1000])
        if check:
            raise SystemExit(f"command failed: {cmd}")
    return proc


def idb(*args, **kw):
    return run(["idb", *args, "--udid", udid], **kw)


def shot(name):
    path = os.path.join(out, f"{name}.png")
    run(["xcrun", "simctl", "io", udid, "screenshot", path])
    return path


def tree():
    proc = subprocess.run(["idb", "ui", "describe-all", "--udid", udid, "--json"], capture_output=True, text=True)
    try:
        data = json.loads(proc.stdout)
        return data if isinstance(data, list) else []
    except Exception:
        # Older idb prints one JSON object per line.
        items = []
        for line in proc.stdout.splitlines():
            try:
                items.append(json.loads(line))
            except Exception:
                pass
        return items


def dump(name):
    items = tree()
    with open(os.path.join(out, f"{name}.json"), "w") as handle:
        json.dump(items, handle, indent=1)
    log(f"tree {name}: {len(items)} elements; labels:", [i.get("AXLabel") for i in items if i.get("AXLabel")][:40])
    return items


def center(item):
    frame = item.get("frame") or {}
    return frame.get("x", 0) + frame.get("width", 0) / 2, frame.get("y", 0) + frame.get("height", 0) / 2


def find(items, pattern):
    rx = re.compile(pattern, re.I)
    for item in items:
        if rx.search(item.get("AXLabel") or "") or rx.search(item.get("AXValue") or ""):
            return item
    return None


def tap(x, y):
    idb("ui", "tap", str(int(x)), str(int(y)))


def swipe(x1, y1, x2, y2, duration=0.4):
    idb("ui", "swipe", str(int(x1)), str(int(y1)), str(int(x2)), str(int(y2)), "--duration", str(duration))


def wait_for(pattern, seconds, step=3):
    deadline = time.time() + seconds
    while time.time() < deadline:
        items = tree()
        hit = find(items, pattern)
        if hit:
            return items, hit
        time.sleep(step)
    return tree(), None


def png_size(path):
    with open(path, "rb") as handle:
        header = handle.read(24)
    return int.from_bytes(header[16:20], "big"), int.from_bytes(header[20:24], "big")


def screen_size():
    """Screen size in points, from a screenshot (pixels / 2 on iPad and most iPhone simulators)."""
    path = shot("_size")
    w, h = png_size(path)
    os.remove(path)
    return w / 2.0, h / 2.0


width, height = screen_size()
log("screen", width, height)


def wake_accessibility():
    """The Compose accessibility tree is empty until something touches the screen: tap a blank corner."""
    tap(width * 0.5, height - 24)
    time.sleep(2)


# ---- get past the sign-in gate ---------------------------------------------------------------
time.sleep(10)
shot("00-launch")
wake_accessibility()
items = dump("00-launch-after-wake")
if not items:
    wake_accessibility()
    items = dump("00-launch-after-wake2")
hit = find(items, r"continue without")
if not hit:
    items, hit = wait_for(r"continue without", 30)
if hit:
    log("tapping 'Continue without account' by label", center(hit))
    tap(*center(hit))
else:
    # Position measured on the iPad simulator: ~62% of the screen height, centred.
    log("label not found; tapping the measured position")
    tap(width * 0.5, height * 0.617)
time.sleep(8)
shot("01-after-auth")
items = dump("01-after-auth")

# profile selection or onboarding: look at what is on screen and tap a sensible element
def has_tabs(its):
    return bool(find(its, r"^home$") and find(its, r"^search$"))


for attempt in range(8):
    items = tree()
    if has_tabs(items):
        break
    shot(f"02-gate-{attempt}")
    dump(f"02-gate-{attempt}")
    labels = [i.get("AXLabel") or "" for i in items]
    log("gate labels", labels[:30])
    prof = find(items, r"^(continue|start|get started|next|skip|done|ok|got it|not now|later)$") \
        or find(items, r"(add profile|profile|who.?s watching)") 
    if prof:
        log("gate: tapping", prof.get("AXLabel"))
        tap(*center(prof))
    else:
        tap(width / 2, height * 0.5)
    time.sleep(6)

log("waiting for the home screen to settle")
time.sleep(25)  # catalogs load; the bar warm-up and the tab pre-compose run a few seconds after launch
items = dump("03-home")
shot("03-home")

# ---- record the scenes -----------------------------------------------------------------------
video_path = os.path.join(out, "scenes.mp4")
recorder = subprocess.Popen(
    ["xcrun", "simctl", "io", udid, "recordVideo", "--codec=h264", "--force", video_path],
    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
)
time.sleep(3)

tabs = {}
for name in ("Home", "Search", "Library"):
    item = find(items, rf"^{name}$")
    if item:
        tabs[name] = center(item)
profile_tab = find(items, r"profile|settings")
if profile_tab:
    tabs["Profile"] = center(profile_tab)
log("tabs", tabs)

mid_x, mid_y = width / 2, height * 0.55

# Scene 1: scroll down (bar compacts), scroll up (bar expands)
for _ in range(3):
    swipe(mid_x, mid_y + 200, mid_x, mid_y - 250, 0.35)
    time.sleep(0.4)
time.sleep(1.5)
shot("10-compact")
for _ in range(3):
    swipe(mid_x, mid_y - 250, mid_x, mid_y + 250, 0.35)
    time.sleep(0.4)
time.sleep(1.5)
shot("11-expanded")

# Scene 2: tap through the tabs
for name in ("Search", "Library", "Home"):
    if name in tabs:
        tap(*tabs[name])
        time.sleep(1.6)
        shot(f"20-tab-{name.lower()}")

# Scene 3: hold and drag the selection from the first to the last tab and back
if "Home" in tabs and "Library" in tabs:
    x1, y1 = tabs["Home"]
    x2, y2 = tabs["Library"]
    swipe(x1, y1, x2, y2, 1.6)
    time.sleep(1.2)
    shot("30-after-drag")
    swipe(x2, y2, x1, y1, 1.6)
    time.sleep(1.2)

# Scene 4: long-press a poster (a stationary swipe holds the finger down). Poster cards are the
# portrait-shaped buttons/images in the upper half of the screen.
poster = None
for item in tree():
    fr = item.get("frame") or {}
    w, h, x, y = fr.get("width", 0), fr.get("height", 0), fr.get("x", 0), fr.get("y", 0)
    if 80 <= w <= 260 and h >= w * 1.25 and 150 <= y <= height * 0.7 and x >= 0 and x + w <= width:
        poster = (x + w / 2, y + h / 2)
        log("poster candidate", item.get("AXLabel"), fr)
        break
px, py = poster if poster else (width * 0.2, height * 0.42)
swipe(px, py, px, py, 1.4)
time.sleep(0.3)
shot("40-long-press")
time.sleep(1.2)
shot("41-long-press-menu")
tap(width * 0.5, height * 0.08)
time.sleep(1.0)

time.sleep(2)
recorder.send_signal(2)  # SIGINT finalises the mp4
try:
    recorder.wait(timeout=30)
except Exception:
    recorder.kill()
log("done")
