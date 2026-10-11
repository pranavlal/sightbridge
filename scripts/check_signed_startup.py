#!/usr/bin/env python3
"""Exercise the installed, shrunk testing APK without test code inside the app.

--restart stops its current media/AI session before opening a fresh dashboard.
"""
import argparse
import subprocess
import time
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="ADB device serial, if multiple devices are connected")
    parser.add_argument("--restart", action="store_true", help="Stop the app before testing launch")
    args = parser.parse_args()
    adb = ["adb"] + (["-s", args.serial] if args.serial else [])
    package = "com.sightbridge.testing"
    dump = "/data/local/tmp/sightbridge-startup-check.xml"

    def shell(*command):
        return subprocess.check_output(adb + ["shell", *command], text=True, timeout=30)

    if args.restart:
        shell("am", "force-stop", package)
    shell("am", "start", "-n", package + "/com.sightbridge.app.MainActivity")
    try:
        # Compose must finish its initial composition: an activity launch response
        # alone previously concealed a fatal missing LocalLifecycleOwner.
        time.sleep(3)
        shell("uiautomator", "dump", dump)
        root = ET.fromstring(shell("cat", dump))
        nodes = [node for node in root.iter("node") if node.get("package") == package]
        texts = {node.get("text") for node in nodes}
        if not nodes or not {"USB Webcam", "Phone Camera"}.issubset(texts):
            raise SystemExit("FAIL: signed app did not reach the camera-selection dashboard")
        time.sleep(2)
        if not shell("pidof", package).strip():
            raise SystemExit("FAIL: signed app exited after showing the dashboard")
        print("PASS: installed signed APK reaches and retains its dashboard")
    finally:
        shell("rm", "-f", dump)


if __name__ == "__main__":
    main()
