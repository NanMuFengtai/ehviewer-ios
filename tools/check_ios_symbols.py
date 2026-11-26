#!/usr/bin/env python3
"""检查 iOS 二进制里有没有「低版本系统解析不到的常量符号」。

背景：用新版 iOS SDK 编译 Kotlin/Native 的 cinterop 时，platform.CoreGraphics
这类包会把高版本才引入的常量（比如 iOS 26 的 kCGAdaptiveMaximumBitDepth）
生成为**强引用**。装到低版本 iOS 上，dyld 启动阶段解析不到就直接 abort，
表现是点开黑屏一下退回桌面，日志里只有一句 Symbol not found。

规则：所有 `_k` 开头的未定义符号，必须满足下面之一
  1. 在 SAFE 白名单里（iOS 15 之前就有的老常量）
  2. 二进制里有同名的 weak 定义（由 iosApp/iosApp/WeakSymbols.c 提供）
否则判失败，提示补 weak 兜底。

用法: check_ios_symbols.py <可执行文件路径>
"""

import struct
import sys

# iOS 15 及更早就存在的常量，逐个确认过
SAFE = {
    "_kCFAllocatorDefault",
    "_kCFAllocatorNull",
    "_kCFGregorianCalendar",
    "_kCFRunLoopCommonModes",
    "_kCFRunLoopDefaultMode",
    "_kCFTypeArrayCallBacks",
    "_kCFTypeDictionaryKeyCallBacks",
    "_kCFTypeDictionaryValueCallBacks",
    "_kCFTypeSetCallBacks",
    "_kCTFontFamilyNameAttribute",
    "_kCTFontFamilyNameKey",
    "_kCTFontFormatAttribute",
    "_kCTFontSlantTrait",
    "_kCTFontStyleNameAttribute",
    "_kCTFontTraitsAttribute",
    "_kCTFontVariationAttribute",
    "_kCTFontVariationAxesAttribute",
    "_kCTFontVariationAxisDefaultValueKey",
    "_kCTFontVariationAxisIdentifierKey",
    "_kCTFontVariationAxisMaximumValueKey",
    "_kCTFontVariationAxisMinimumValueKey",
    "_kCTFontWeightTrait",
    "_kCTFontWidthTrait",
    "_kIOSurfaceAllocSize",
    "_kIOSurfaceBytesPerElement",
    "_kIOSurfaceBytesPerRow",
    "_kIOSurfaceHeight",
    "_kIOSurfacePixelFormat",
    "_kIOSurfaceWidth",
}

N_UNDF = 0x0
N_WEAK_DEF = 0x0080


def parse(path):
    data = open(path, "rb").read()
    if data[:4] != b"\xcf\xfa\xed\xfe":
        raise SystemExit("不是 64 位小端 Mach-O: " + path)

    ncmds = struct.unpack("<I", data[16:20])[0]
    off = 32
    symtab = None
    for _ in range(ncmds):
        cmd, size = struct.unpack("<II", data[off:off + 8])
        if cmd == 0x2:  # LC_SYMTAB
            symtab = struct.unpack("<IIII", data[off + 8:off + 24])
        off += size
    if symtab is None:
        raise SystemExit("找不到 LC_SYMTAB")

    symoff, nsyms, stroff, strsize = symtab
    strtab = data[stroff:stroff + strsize]

    undef, weak_def = set(), set()
    for i in range(nsyms):
        p = symoff + i * 16
        n_strx, n_type, _n_sect, n_desc, _n_value = struct.unpack("<IBBHQ", data[p:p + 16])
        end = strtab.find(b"\x00", n_strx)
        name = strtab[n_strx:end].decode("utf-8", "replace")
        if n_type & 0x0E == N_UNDF:
            undef.add(name)
        if n_desc & N_WEAK_DEF:
            weak_def.add(name)
    return undef, weak_def


def main():
    if len(sys.argv) != 2:
        raise SystemExit("用法: check_ios_symbols.py <可执行文件>")
    undef, weak_def = parse(sys.argv[1])

    risky = sorted(
        n for n in undef
        if n.startswith("_k") and n not in SAFE and n not in weak_def
    )

    consts = sorted(n for n in undef if n.startswith("_k"))
    print(f"未定义符号 {len(undef)} 个，其中常量类 {len(consts)} 个")
    print(f"已由 weak 兜底的常量: {sorted(n for n in consts if n in weak_def)}")

    if risky:
        print("\n以下常量没有 weak 兜底，老版本 iOS 启动时会 dyld 失败：")
        for n in risky:
            print("  " + n)
        print("\n去 iosApp/iosApp/WeakSymbols.c 里补一个 weak 定义，确认本项目不使用它。")
        sys.exit(1)

    print("符号检查通过")


if __name__ == "__main__":
    main()
