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
SPRITE_COLOR = bytes([99, 125, 250, 255])
MENU_COLOR = bytes([38, 58, 82, 255])


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


def screenshot():
    raw = adb("exec-out", "screencap")
    width, height, pixel_format = struct.unpack_from("<III", raw)
    assert pixel_format in (1, 2), f"Unexpected screenshot format {pixel_format}"
    start = len(raw) - width * height * 4
    assert start in (12, 16), f"Unexpected screenshot header size {start}"
    return width, raw[start:]


def color_center(color, before, after):
    width, pixels = after
    previous_width, previous_pixels = before
    assert width == previous_width and len(pixels) == len(previous_pixels), "Screen bounds changed"
    offset = 0
    count = x_sum = y_sum = 0
    while (match := pixels.find(color, offset)) != -1:
        offset = match + 1
        if match % 4 or previous_pixels[match:match + 4] == color:
            continue
        y, x = divmod(match // 4, width)
        count += 1
        x_sum += x
        y_sum += y
    if count >= 20:
        return x_sum // count, y_sum // count
    return None


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
    background = screenshot()

    print(shell("am", "start", "-W", "-n", ACTIVITY), flush=True)
    tap_label("开启悬浮宠物")
    wait_for("the first rendered pet frame", lambda: find_label("悬浮已开启"))

    shell("input", "keyevent", "KEYCODE_HOME")
    time.sleep(1)

    def rendered_on_home():
        frame = screenshot()
        position = color_center(SPRITE_COLOR, background, frame)
        if position and has_window():
            return position, frame
        return None

    (x, y), idle_frame = wait_for("pet pixels and overlay window on Android Home", rendered_on_home)
    print(f"Pet sprite rendered over the home screen at ({x}, {y}).", flush=True)
    assert color_center(MENU_COLOR, background, idle_frame) is None, "Menu is visible before long press"

    shell("input", "swipe", str(x), str(y), str(x), str(y), "850")
    menu_x, menu_y = wait_for("menu after long press", lambda: color_center(MENU_COLOR, idle_frame, screenshot()))
    shell("input", "tap", str(menu_x), str(menu_y))
    wait_for("the overlay window to be removed by the long-press menu", lambda: not has_window())

    shell("am", "start", "-W", "-n", ACTIVITY)
    wait_for("overlay to be available to start again", lambda: find_label("开启悬浮宠物"))
    print("Long-press menu closed the pet; the app is ready to start it again.", flush=True)


if __name__ == "__main__":
    try:
        main(sys.argv[1])
    except Exception:
        print(shell("logcat", "-d", "-s", "PetOverlay", "AndroidRuntime")[-9000:], file=sys.stderr)
        raise
