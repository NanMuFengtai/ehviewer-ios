# -*- coding: utf-8 -*-
"""分析禁漫页图：尺寸、是否打乱、各段数还原后的接缝连续性。"""
import os
import sys

import numpy as np
from PIL import Image

OUT = r"D:\dev\EhViewer\out\jmprobe"


def strips(img, num):
    """按禁漫客户端规则重排：h/num 切段、余数并入第一段、倒序摆回。"""
    w, h = img.size
    move = h // num
    over = h % num
    out = Image.new("RGB", (w, h))
    for i in range(num):
        src_y = h - move * (i + 1) - over
        dst_y = move * i + (0 if i == 0 else over)
        height = move + over if i == 0 else move
        if src_y < 0 or src_y + height > h or dst_y + height > h:
            continue
        out.paste(img.crop((0, src_y, w, src_y + height)), (0, dst_y))
    return out


def seam_score(img, num):
    """段边界处的行差越小，越可能是正确还原。"""
    arr = np.asarray(img.convert("L"), dtype=np.float32)
    h, w = arr.shape
    move = h // num
    over = h % num
    diffs = []
    for i in range(1, num):
        y = move * i + over
        if 0 < y < h:
            diffs.append(float(np.abs(arr[y] - arr[y - 1]).mean()))
    return float(np.mean(diffs)) if diffs else 0.0


def natural_baseline(img):
    """自然图像相邻行的平均差，作为参照。"""
    arr = np.asarray(img.convert("L"), dtype=np.float32)
    return float(np.abs(np.diff(arr, axis=0)).mean())


def main(path):
    img = Image.open(path)
    print("size:", img.size, "mode:", img.mode)
    img = img.convert("RGB")
    w, h = img.size
    print("baseline row-diff: %.2f" % natural_baseline(img))
    os.makedirs(OUT, exist_ok=True)
    img.save(os.path.join(OUT, "orig.png"))
    for num in range(2, 21, 2):
        fixed = strips(img, num)
        score = seam_score(fixed, num)
        name = "num%02d.png" % num
        fixed.save(os.path.join(OUT, name))
        print("num=%2d  seam=%.2f  -> %s" % (num, score, name))


if __name__ == "__main__":
    main(sys.argv[1])
