#!/usr/bin/env python3
"""Capture the actual effects APK on an isolated Android emulator."""

from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET


OUTPUT = Path("artifacts/design")
PACKAGE = "com.kzvpn.app.v032.fx"


def adb(*args):
    return subprocess.check_output(["adb", *args])


def hierarchy(name):
    adb("shell", "uiautomator", "dump", "/sdcard/eneida-window.xml")
    xml = adb("exec-out", "cat", "/sdcard/eneida-window.xml")
    (OUTPUT / (name + ".xml")).write_bytes(xml)
    return ET.fromstring(xml)


def screenshot(name):
    (OUTPUT / (name + ".png")).write_bytes(adb("exec-out", "screencap", "-p"))


def tap_text(value):
    for attempt in range(3):
        root = hierarchy("navigation-" + str(attempt))
        for node in root.iter("node"):
            if node.get("text") == value:
                bounds = [int(x) for x in re.findall(r"\d+", node.get("bounds", ""))]
                if len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1]:
                    adb("shell", "input", "tap", str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))
                    return
        adb("shell", "input", "swipe", "540", "1850", "540", "900", "350")
    raise RuntimeError("Missing visible navigation label: " + value)


def resource_point(root, name):
    for node in root.iter("node"):
        if node.get("resource-id", "").endswith("/" + name):
            b = [int(x) for x in re.findall(r"\d+", node.get("bounds", ""))]
            if len(b) == 4:
                return str((b[0] + b[2]) // 2), str((b[1] + b[3]) // 2)
    raise RuntimeError("Missing gallery button: " + name)


def tap_resource(name):
    adb("shell", "input", "tap", *resource_point(hierarchy("tap-" + name), name))


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    adb("install", "-r", "app/build/outputs/apk/effects/app-effects.apk")
    adb("shell", "wm", "size", "1080x2340")
    adb("shell", "wm", "density", "420")
    for key in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
        adb("shell", "settings", "put", "global", key, "0")
    adb("shell", "settings", "put", "system", "font_scale", "1.0")
    adb("logcat", "-c")
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/com.kzvpn.app.MainActivity")
    time.sleep(4)
    root = hierarchy("home")
    text = " ".join(node.get("text", "") for node in root.iter("node"))
    for label in ("Eneida", "VPN отключён", "FX"):
        if label not in text:
            raise RuntimeError("Home screen did not render: " + label)
    screenshot("home")
    tap_text("Серверы")
    time.sleep(2)
    root = hierarchy("servers")
    if not any("Добавить сервер" in node.get("text", "") for node in root.iter("node")):
        raise RuntimeError("Server picker did not open")
    screenshot("servers")
    adb("shell", "input", "keyevent", "4")
    adb("shell", "settings", "put", "system", "font_scale", "1.3")
    time.sleep(3)
    screenshot("home-large-text")
    hierarchy("home-large-text")
    # The effects-only gallery is explicitly labelled as a visual demonstration.
    adb("shell", "settings", "put", "system", "font_scale", "1.0")
    adb("shell", "wm", "size", "1920x1080")
    adb("shell", "wm", "density", "320")
    adb("shell", "settings", "put", "global", "animator_duration_scale", "1")
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/com.kzvpn.app.ButtonGalleryActivity")
    time.sleep(3)
    adb("shell", "input", "tap", "960", "140")
    root = hierarchy("buttons-reference")
    texts = " ".join(node.get("text", "") for node in root.iter("node"))
    for value in ("Включить", "Выключить", "Демонстрация"):
        if value not in texts:
            raise RuntimeError("Button gallery did not render: " + value)
    screenshot("buttons-reference")
    off_point = resource_point(root, "demo_power_off")
    on_point = resource_point(root, "demo_power_on")
    recording = subprocess.Popen([
        "adb", "shell", "screenrecord", "--size", "1280x720", "--bit-rate", "4000000",
        "--time-limit", "9", "/sdcard/eneida-effects.mp4"
    ], stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    time.sleep(0.8)
    adb("shell", "input", "tap", *off_point)
    time.sleep(2.2)
    adb("shell", "input", "tap", *off_point)
    time.sleep(2.2)
    adb("shell", "input", "tap", *on_point)
    error = recording.communicate(timeout=15)[1]
    if recording.returncode:
        raise RuntimeError("Emulator screen recording failed: " + error.decode(errors="replace")[:300])
    video = adb("exec-out", "cat", "/sdcard/eneida-effects.mp4")
    if len(video) < 1000:
        raise RuntimeError("Empty effects recording")
    (OUTPUT / "button-effects.mp4").write_bytes(video)
    # Also verify the reduced-motion setting on the same native view.
    adb("shell", "settings", "put", "global", "animator_duration_scale", "0")
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/com.kzvpn.app.ButtonGalleryActivity")
    time.sleep(2)
    tap_resource("demo_power_off")
    time.sleep(1.2)
    root = hierarchy("buttons-reduced-motion")
    button = next(node for node in root.iter("node") if node.get("resource-id", "").endswith("/demo_power_off"))
    if button.get("text") != "Выключить":
        raise RuntimeError("Reduced-motion transition did not reach its final state")
    screenshot("buttons-reduced-motion")
    (OUTPUT / "logcat.txt").write_bytes(adb("logcat", "-d", "-t", "500"))
    print("Captured Android screens, both power states, transition video and reduced-motion mode.")


if __name__ == "__main__":
    main()
