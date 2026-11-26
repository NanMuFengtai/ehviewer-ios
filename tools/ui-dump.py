#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""dump 当前界面文字 + 中心坐标（点击用）。

用法：python tools/ui-dump.py [关键字过滤]
"""
import re
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ADB = r"D:\dev\android-toolchain\sdk\platform-tools\adb.exe"


def main(keyword=""):
    subprocess.run([ADB, "shell", "uiautomator", "dump", "/sdcard/ui.xml"],
                   capture_output=True)
    xml = subprocess.run([ADB, "shell", "cat", "/sdcard/ui.xml"],
                         capture_output=True).stdout.decode("utf-8", "ignore")
    for node in re.findall(r"<node[^>]*>", xml):
        m = re.search(r'text="([^"]*)"', node)
        if not m or not m.group(1).strip():
            continue
        text = m.group(1)
        if keyword and keyword not in text:
            continue
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
        if not b:
            continue
        x = (int(b.group(1)) + int(b.group(3))) // 2
        y = (int(b.group(2)) + int(b.group(4))) // 2
        click = "click" if 'clickable="true"' in node else ""
        print("%-52s %5d,%-5d %s" % (text[:52], x, y, click))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "")
