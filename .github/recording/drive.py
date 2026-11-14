#!/usr/bin/env python3
"""Drives the Nuvio app in an iOS simulator with idb (real taps and swipes) and records
screenshots and a video of the navigation bar, scrolling and long-press scenes.

Usage: drive.py <udid> <bundle id> <output dir>
Buttons are found by reading the screenshots with Apple's text recognition (OCR_TOOL, built from
ocr.swift) because the app's accessibility tree is often empty in the simulator. The accessibility
tree is still dumped when it is available. Everything it learns is written to the output dir.
"""
import json
import os
import re
import subprocess
import sys
import time

udid, bundle, out = sys.argv[1], sys.argv[2], sys.argv[3]
OCR_TOOL = os.environ.get("OCR_TOOL", "")
os.makedirs(out, exist_ok=True)
LOG = open(os.path.join(out, "driver.log"), "a", buffering=1)


def log(*parts):
    line = " ".join(str(p) for p in parts)
    print(line, flush=True)
    LOG.write(line + "\n")


def run(cmd, timeout=180):
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    if proc.returncode != 0:
        log("exit", proc.returncode, " ".join(cmd)[:120], (proc.stderr or "").strip()[:300])
    return proc


def idb(*args, timeout=180):
    return run(["idb", *args, "--udid", udid], timeout=timeout)


def shot(name):
    path = os.path.join(out, f"{name}.png")
    run(["xcrun", "simctl", "io", udid, "screenshot", path])
    return path


def png_size(path):
    with open(path, "rb") as handle:
        header = handle.read(24)
    return int.from_bytes(header[16:20], "big"), int.from_bytes(header[20:24], "big")


def tap(x, y):
    log(f"tap {int(x)},{int(y)}")
    idb("ui", "tap", str(int(x)), str(int(y)))


def swipe(x1, y1, x2, y2, duration=0.4):
    log(f"swipe {int(x1)},{int(y1)} -> {int(x2)},{int(y2)} in {duration}s")
    idb("ui", "swipe", str(int(x1)), str(int(y1)), str(int(x2)), str(int(y2)), "--duration", str(duration))


def type_text(text):
    log("type", text)
    idb("ui", "text", text)


# ---- reading the screen ----------------------------------------------------------------------
def ocr(path):
    if not OCR_TOOL:
        return []
    proc = subprocess.run([OCR_TOOL, path], capture_output=True, text=True)
    try:
        return json.loads(proc.stdout)
    except Exception:
        log("ocr failed", proc.stderr[:200])
        return []


def look(name):
    """Screenshot + OCR; returns the list of text boxes (points)."""
    path = shot(name)
    items = ocr(path)
    log(f"[{name}] text:", [i["text"] for i in items][:40])
    return items


def find_text(items, pattern, region=None):
    rx = re.compile(pattern, re.I)
    for item in items:
        if rx.search(item["text"]):
            if region and not region(item):
                continue
            return item
    return None


def center(item):
    return item["x"] + item["w"] / 2, item["y"] + item["h"] / 2


def tap_item(item):
    tap(*center(item))


size_path = shot("_size")
width, height = [v / 2.0 for v in png_size(size_path)]
os.remove(size_path)
log("screen", width, height)

# ---- get to the main screen: sign-in -> profile -> home -------------------------------------
time.sleep(10)
items = look("00-launch")


def on_springboard(its):
    words = {i["text"].strip() for i in its}
    return {"Contacts", "Shortcuts", "Health"} <= words


relaunches = 0


def relaunch():
    """The app died (crash or kill): start it again; saved state (sign-in, profile) is kept."""
    global relaunches
    relaunches += 1
    log(f"app is not on screen (crash?) - relaunch #{relaunches}")
    env = dict(os.environ, SIMCTL_CHILD_NUVIO_FORCE_COMPOSE_SHELL="1")
    subprocess.run(["xcrun", "simctl", "launch", udid, bundle], env=env, capture_output=True, text=True)
    time.sleep(25)


def on_main(its):
    """The tab bar words are on screen (bottom bar or top bar)."""
    words = {i["text"].strip().lower() for i in its}
    return {"home", "search", "library"} <= words


profile_name = "Rec"
typed_name = False
create_taps = 0
created = False
for step in range(40):
    items = look(f"02-gate-{step:02d}")
    if on_main(items):
        log("main screen reached")
        break
    if on_springboard(items):
        if relaunches >= 4:
            log("giving up: the app keeps disappearing")
            break
        relaunch()
        continue
    texts = " | ".join(i["text"] for i in items).lower()
    on_form = "choose an avatar" in texts or "custom avatar url" in texts
    if find_text(items, r"^continue without account"):
        tap_item(find_text(items, r"^continue without account"))
        time.sleep(15)  # anonymous sign-in goes over the network and is slow on CI
        continue
    if on_form:
        if not typed_name:
            field = find_text(items, r"^profile name$")
            tap(*(center(field) if field else (width * 0.5, height * 0.236)))
            time.sleep(2)
            type_text(profile_name)
            typed_name = True
            time.sleep(4)
            continue
        button = find_text(items, r"^create profile$|^save profile$")
        if button:
            tap_item(button)
            create_taps += 1
            created = True
            time.sleep(12)  # creating a profile goes over the network
            continue
        time.sleep(5)
        continue
    # Manage mode ("Add Profile" tile + "Done"): add the profile first, press Done only afterwards.
    if find_text(items, r"^add profile$") and not created:
        tap_item(find_text(items, r"^add profile$"))
        time.sleep(6)
        continue
    if find_text(items, r"^done$") and created:
        tap_item(find_text(items, r"^done$"))
        time.sleep(5)
        continue
    if "who" in texts and "watching" in texts:
        card = find_text(items, rf"^{profile_name}$") if created else None
        if card:
            tap_item(card)
            time.sleep(12)
            continue
        add = find_text(items, r"^add profile$")
        if add:
            tap_item(add)
            time.sleep(5)
            continue
        manage = find_text(items, r"manage profiles")
        if manage:
            tap_item(manage)
            time.sleep(5)
            continue
    if find_text(items, r"^add profile$"):
        tap_item(find_text(items, r"^add profile$"))
        time.sleep(5)
        continue
    time.sleep(8)

log("letting the home screen settle (catalogs load, the bar warm-up and tab pre-compose run)")
time.sleep(30)
items = look("03-home")

# ---- record the scenes -----------------------------------------------------------------------
video_path = os.path.join(out, "scenes.mp4")
recorder = subprocess.Popen(
    ["xcrun", "simctl", "io", udid, "recordVideo", "--codec=h264", "--force", video_path],
    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
)
time.sleep(3)

tabs = {}
for name in ("Home", "Search", "Library"):
    item = find_text(items, rf"^{name}$")
    if item:
        tabs[name] = center(item)
prof = find_text(items, r"^(profile|settings|{})$".format(profile_name))
if prof:
    tabs["Profile"] = center(prof)
log("tabs", tabs)

mid_x, mid_y = width / 2, height * 0.55

# Scene 1: scroll down (bar compacts), scroll up (bar expands)
for _ in range(3):
    swipe(mid_x, mid_y + 200, mid_x, mid_y - 250, 0.35)
    time.sleep(0.4)
time.sleep(1.5)
look("10-compact")
for _ in range(3):
    swipe(mid_x, mid_y - 250, mid_x, mid_y + 250, 0.35)
    time.sleep(0.4)
time.sleep(1.5)
look("11-expanded")

# Scene 2: tap through the tabs
for name in ("Search", "Library", "Home"):
    if name in tabs:
        tap(*tabs[name])
        time.sleep(2.5)
        look(f"20-tab-{name.lower()}")

# Scene 3: hold and drag the selection from the first to the last tab and back
if "Home" in tabs and "Library" in tabs:
    x1, y1 = tabs["Home"]
    x2, y2 = tabs["Library"]
    swipe(x1, y1, x2, y2, 1.6)
    time.sleep(1.5)
    shot("30-after-drag")
    swipe(x2, y2, x1, y1, 1.6)
    time.sleep(1.5)

# Scene 4: long-press a poster (a stationary swipe holds the finger down). The position is a guess
# (first poster row); it is corrected from the screenshots between runs.
px, py = width * float(os.environ.get("POSTER_X", "0.2")), height * float(os.environ.get("POSTER_Y", "0.42"))
swipe(px, py, px, py, 1.4)
time.sleep(0.3)
shot("40-long-press")
time.sleep(1.2)
shot("41-long-press-menu")
tap(width * 0.5, height * 0.06)
time.sleep(1.0)

time.sleep(2)
recorder.send_signal(2)  # SIGINT finalises the mp4
try:
    recorder.wait(timeout=30)
except Exception:
    recorder.kill()
log("done")
