#!/usr/bin/env python3
"""Capture the actual preview APK on an isolated Android emulator."""

from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET


OUTPUT = Path("artifacts/design")
PACKAGE = "com.kzvpn.app.v032.preview"


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


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    adb("install", "-r", "app/build/outputs/apk/preview/app-preview.apk")
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
    for label in ("Eneida", "VPN отключён", "PREVIEW"):
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
    (OUTPUT / "logcat.txt").write_bytes(adb("logcat", "-d", "-t", "500"))
    print("Captured native Android home, server picker and enlarged text.")


if __name__ == "__main__":
    main()
