# -*- coding: utf-8 -*-
"""禁漫专线：拉接口 / 下原图 / 试各种去乱序段数，用来确认真实规则。

用法：
  python jm_probe.py album 1233797
  python jm_probe.py chapter 1233797 1
  python jm_probe.py raw <chapterId> <fileName> [page]
"""
import base64
import hashlib
import json
import os
import sys
import time
import urllib.parse
import urllib.request

from Crypto.Cipher import AES

SECRET = "185Hcomic3PAPP7R"
VERSION = "2.1.13"
HOSTS = ["www.cdnhjk.net", "www.cdngwc.cc", "www.cdngwc.net", "www.cdngwc.club"]
IMG_HOSTS = [
    "cdn-msp3.18comic.vip",
    "cdn-msp2.18comic.vip",
    "cdn-msp.18comic.vip",
    "cdn-msp.jmapiproxy1.cc",
    "cdn-msp2.jmapiproxy2.cc",
    "cdn-msp3.jmapinodeudzn.net",
]
UA = (
    "Mozilla/5.0 (Linux; Android 9; V1938CT Build/PQ3A.190705.11211812; wv) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/91.0.4472.114 Safari/537.36"
)
PROXY = os.environ.get("JM_PROXY", "http://127.0.0.1:7890")
OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "out", "jmprobe")

opener = urllib.request.build_opener(
    urllib.request.ProxyHandler({"http": PROXY, "https": PROXY})
)


def md5(text):
    return hashlib.md5(text.encode()).hexdigest()


def decrypt(data, ts):
    key = md5("%s%s" % (ts, SECRET)).encode()
    cipher = AES.new(key, AES.MODE_ECB)
    plain = cipher.decrypt(base64.b64decode(data))
    pad = plain[-1] if isinstance(plain[-1], int) else ord(plain[-1])
    return plain[: len(plain) - min(pad, 32)].decode("utf-8", "ignore")


def get_headers(ts):
    return {
        "User-Agent": UA,
        "token": md5("%s%s" % (ts, SECRET)),
        "tokenparam": "%s,%s" % (ts, VERSION),
        "Accept": "application/json",
    }


def api(path, params=None):
    query = ""
    if params:
        query = "?" + urllib.parse.urlencode(params)
    last = None
    for host in HOSTS:
        ts = int(time.time())
        url = "https://%s%s%s" % (host, path, query)
        try:
            req = urllib.request.Request(url, headers=get_headers(ts))
            text = opener.open(req, timeout=25).read().decode("utf-8", "ignore")
            env = json.loads(text)
            return json.loads(decrypt(env["data"], ts))
        except Exception as exc:  # noqa: BLE001
            last = exc
            print("  ! %s %s" % (host, exc), file=sys.stderr)
    raise last


def fetch_image(url):
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": UA,
            "Referer": "https://18comic.vip/",
        },
    )
    return opener.open(req, timeout=60).read()


def save(name, data):
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, name)
    with open(path, "wb") as handle:
        handle.write(data)
    print("saved %s (%d bytes)" % (path, len(data)))
    return path


def cmd_album(album_id):
    data = api("/album", {"id": album_id})
    print(json.dumps({k: data[k] for k in ("id", "name", "total_photos") if k in data}, ensure_ascii=False))
    series = data.get("series") or []
    print("series:", len(series))
    for item in series[:10]:
        print("  sort=%s id=%s name=%s" % (item.get("sort"), item.get("id"), (item.get("name") or "")[:40]))


def cmd_chapter(chapter_id):
    data = api("/chapter", {"id": chapter_id})
    print(json.dumps({k: data[k] for k in ("id", "name") if k in data}, ensure_ascii=False))
    images = data.get("images") or []
    print("pages:", len(images), "first:", images[:3], "last:", images[-2:])


def cmd_raw(chapter_id, file_name, page="1"):
    url_tail = "/media/photos/%s/%s" % (chapter_id, file_name)
    last = None
    for host in IMG_HOSTS:
        try:
            data = fetch_image("https://%s%s" % (host, url_tail))
            print("host %s ok %d bytes" % (host, len(data)))
            return save("raw_%s_%s" % (chapter_id, file_name), data)
        except Exception as exc:  # noqa: BLE001
            last = exc
            print("  ! %s %s" % (host, exc), file=sys.stderr)
    raise last


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "help"
    if cmd == "album":
        cmd_album(sys.argv[2])
    elif cmd == "chapter":
        cmd_chapter(sys.argv[2])
    elif cmd == "raw":
        cmd_raw(sys.argv[2], sys.argv[3])
    else:
        print(__doc__)
