"""Exercise the actual overlay window on a fresh Android emulator."""
import re
import struct
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PACKAGE = "com.resker666.yeshunguangpet"
ACTIVITY = f"{PACKAGE}/.MainActivity"
WINDOW = "PocketPetOverlay"
SPRITE_COLORS = (bytes([120, 200, 189, 255]), bytes([255, 240, 182, 255]))


def adb(*args):
    return subprocess.check_output(["adb", *args], timeout=25)


def shell(*args):
    return adb("shell", *args).decode(errors="replace")


def wait_for(description, observe, timeout=20):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        result = observe()
        if result:
            return result
        time.sleep(0.5)
    raise AssertionError(f"Timed out waiting for {description}")


def find_label(label):
    path = "/sdcard/pocket-pet-smoke.xml"
    if "dumped" not in shell("uiautomator", "dump", path):
        return None
    nodes = ET.fromstring(shell("cat", path)).iter("node")
    return next((node.attrib for node in nodes if node.get("text") == label), None)


def tap_label(label):
    node = wait_for(label, lambda: find_label(label))
    left, top, right, bottom = map(int, re.findall(r"\d+", node["bounds"]))
    shell("input", "tap", str((left + right) // 2), str((top + bottom) // 2))


def sprite_colors():
    raw = adb("exec-out", "screencap")
    width, height, pixel_format = struct.unpack_from("<III", raw)
    assert pixel_format in (1, 2), f"Unexpected screenshot format {pixel_format}"
    start = len(raw) - width * height * 4
    assert start in (12, 16), f"Unexpected screenshot header size {start}"
    return tuple(raw.count(color, start) for color in SPRITE_COLORS)


def has_window():
    return WINDOW in shell("dumpsys", "window", "windows")


def main(apk):
    adb("install", "-r", apk)
    shell("input", "keyevent", "KEYCODE_WAKEUP")
    shell("wm", "dismiss-keyguard")
    shell("input", "keyevent", "KEYCODE_HOME")
    shell("appops", "set", PACKAGE, "SYSTEM_ALERT_WINDOW", "allow")
    shell("pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")
    time.sleep(2)
    background = sprite_colors()

    print(shell("am", "start", "-W", "-n", ACTIVITY), flush=True)
    tap_label("开启悬浮宠物")
    wait_for("the first rendered pet frame", lambda: find_label("悬浮已开启"))

    shell("input", "keyevent", "KEYCODE_HOME")
    time.sleep(1)

    def rendered_on_home():
        colors = sprite_colors()
        if all(after > before + 20 for before, after in zip(background, colors)) and has_window():
            return colors
        return None

    colors = wait_for("pet pixels and an overlay window on the Android home screen", rendered_on_home)
    print(f"Pet sprite rendered over the home screen: {background} -> {colors}", flush=True)

    shell("am", "start", "-W", "-n", ACTIVITY)
    tap_label("收起悬浮宠物")
    wait_for("the overlay window to be removed", lambda: not has_window())
    wait_for("overlay to be available to start again", lambda: find_label("开启悬浮宠物"))
    print("Pet overlay closed and is ready to be started again.", flush=True)


if __name__ == "__main__":
    try:
        main(sys.argv[1])
    except Exception:
        print(shell("logcat", "-d", "-s", "PetOverlay", "AndroidRuntime")[-9000:], file=sys.stderr)
        raise
