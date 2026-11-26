# -*- coding: utf-8 -*-
"""试禁漫 /forum 分页能拉到多少条评论。

用法：python jm_comments_probe.py <albumId> [最多页数]
"""
import sys

sys.path.insert(0, r"D:\dev\EhViewer\tools")
import jm_probe as p  # noqa: E402


def main(album_id, max_page=10):
    seen = set()
    for page in range(1, max_page + 1):
        try:
            data = p.api("/forum", {"mode": "all", "page": page, "aid": album_id})
        except Exception as exc:  # noqa: BLE001
            print("page", page, "ERR", exc)
            return
        items = data.get("list") or []
        total = data.get("total")
        new = [c for c in items if (c.get("CID") or c.get("cid")) not in seen]
        for c in new:
            seen.add(c.get("CID") or c.get("cid"))
        print("page %d: list=%d total=%s new=%d" % (page, len(items), total, len(new)))
        if items:
            last = items[-1]
            print("   last:", (last.get("username") or "")[:16], (last.get("addtime") or ""), (last.get("content") or "")[:30])
        if not new:
            break


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "1485719",
         int(sys.argv[2]) if len(sys.argv) > 2 else 10)
