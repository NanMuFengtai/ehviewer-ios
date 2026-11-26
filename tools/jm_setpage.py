# -*- coding: utf-8 -*-
"""把模拟器里禁漫历史的 lastPage 改成指定页（index 从 0 起）。用法：python jm_setpage.py <albumId> <pageIndex>"""
import base64
import json
import subprocess
import sys

ADB = r"D:\dev\android-toolchain\sdk\platform-tools\adb.exe"
PKG = "moe.tarsin.ehviewer.debug"

album_id = sys.argv[1]
page = int(sys.argv[2])

raw = subprocess.run(
    [ADB, "shell", "run-as", PKG, "cat", "files/jm/history.json"],
    capture_output=True,
).stdout.decode("utf-8")
data = json.loads(raw)
hit = False
for item in data:
    if str(item.get("albumId")) == str(album_id):
        item["lastPage"] = page
        hit = True
print("matched" if hit else "NOT FOUND", [ (d.get("albumId"), d.get("lastPage")) for d in data ])

payload = base64.b64encode(json.dumps(data, ensure_ascii=False).encode()).decode()
subprocess.run(
    [ADB, "shell", "run-as", PKG, "sh", "-c", "'echo %s | base64 -d > files/jm/history.json'" % payload],
    check=True,
)
back = subprocess.run(
    [ADB, "shell", "run-as", PKG, "cat", "files/jm/history.json"],
    capture_output=True,
).stdout.decode("utf-8")
print(back[:200])
