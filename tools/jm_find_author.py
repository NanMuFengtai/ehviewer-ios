# -*- coding: utf-8 -*-
"""找多作者本子：python jm_find_author.py "代孕媽媽"
打印搜索结果里 /album 作者数组长度 > 1 的本子（id / 名字 / 作者列表）。"""
import json
import sys

sys.path.insert(0, r"D:\dev\EhViewer\tools")
import jm_probe as p  # noqa: E402


def main(keyword):
    data = p.api("/search", {"search_query": keyword, "page": 1})
    items = data.get("content") or []
    print("hits:", len(items))
    for item in items[:15]:
        aid = item.get("id")
        try:
            album = p.api("/album", {"id": aid})
        except Exception as exc:  # noqa: BLE001
            print("  ! album", aid, exc)
            continue
        author = album.get("author")
        author = [author] if isinstance(author, str) else (author or [])
        name = (album.get("name") or "").strip()
        print(json.dumps({"id": aid, "name": name[:36], "author": author,
                          "series": len(album.get("series") or [])},
                         ensure_ascii=False))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "代孕媽媽")
