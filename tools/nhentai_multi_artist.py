# -*- coding: utf-8 -*-
"""找一个有多个 artist 标签的 nhentai 本子（验证作者斜杠可点）。

用法：python nhentai_multi_artist.py [最多扫描本数]
"""
import json
import sys
import urllib.request

PROXY = "http://127.0.0.1:7890"
BASE = "https://nhentai.net/api/v2"
opener = urllib.request.build_opener(
    urllib.request.ProxyHandler({"http": PROXY, "https": PROXY}))
opener.addheaders = [("User-Agent", "EhViewer-test/1.0"), ("Accept", "application/json")]


def get(path):
    with opener.open(BASE + path, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8", "ignore"))


def main(limit=60):
    seen = 0
    for page in range(1, 8):
        items = get("/galleries?per_page=50&page=%d" % page).get("result") or []
        for item in items:
            seen += 1
            if seen > limit:
                return
            try:
                detail = get("/galleries/%s" % item["id"])
            except Exception as exc:  # noqa: BLE001
                print("skip", item["id"], exc)
                continue
            artists = [t["name"] for t in detail.get("tags", []) if t.get("type") == "artist"]
            if len(artists) >= 2:
                print(json.dumps({
                    "id": item["id"],
                    "title": (detail.get("title") or {}).get("pretty", "")[:60],
                    "artists": artists,
                }, ensure_ascii=False))
                return
    print("not found in", seen)


if __name__ == "__main__":
    main(int(sys.argv[1]) if len(sys.argv) > 1 else 60)
