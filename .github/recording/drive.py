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


# Screen size in points: use the screenshot size of the simulator via the tree root, falling back to simctl.
def screen_size():
    items = tree()
    for item in items:
        frame = item.get("frame") or {}
        if item.get("type") == "Application" and frame.get("width"):
            return frame["width"], frame["height"]
    proc = subprocess.run(["xcrun", "simctl", "list", "devices", "-j"], capture_output=True, text=True)
    return 820.0, 1180.0


width, height = screen_size()
log("screen", width, height)

# ---- get past the sign-in gate ---------------------------------------------------------------
time.sleep(8)
shot("00-launch")
dump("00-launch")
items, hit = wait_for(r"continue without", 90)
if hit:
    log("tapping 'Continue without account'")
    tap(*center(hit))
    time.sleep(6)
else:
    log("no sign-in button found; continuing")
shot("01-after-auth")
items = dump("01-after-auth")

# profile selection or onboarding: tap the first profile-like element when there is no tab bar yet
for attempt in range(6):
    items = tree()
    if find(items, r"^(home|search|library)$"):
        break
    shot(f"02-gate-{attempt}")
    prof = find(items, r"(profile|continue|start|get started|next|skip)")
    if prof:
        tap(*center(prof))
    else:
        tap(width / 2, height / 2)
    time.sleep(5)

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

# Scene 4: long-press a poster (a stationary swipe holds the finger down)
swipe(width * 0.22, height * 0.42, width * 0.22, height * 0.42, 1.4)
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
