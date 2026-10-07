#!/usr/bin/env python3
"""核对实际 APK 的 DEX 压缩，避免构建成功却交付未压缩产物。"""
import argparse
from pathlib import Path
import re
import zipfile
import zlib


def check(apk: Path) -> None:
    with zipfile.ZipFile(apk) as archive:
        dex = [item for item in archive.infolist()
               if re.fullmatch(r"classes(?:\d+)?\.dex", item.filename)]
        if not any(item.filename == "classes.dex" for item in dex):
            raise ValueError("APK 缺少主 DEX")
        if any(item.compress_type != zipfile.ZIP_DEFLATED for item in dex):
            raise ValueError("APK 含未压缩 DEX，请检查 mobile 的 dex.useLegacyPackaging")
        # 读完整 DEX 校验 ZIP CRC，不解包或打印认证资产。
        for item in dex:
            with archive.open(item) as stream:
                while stream.read(1024 * 1024):
                    pass
        raw = sum(item.file_size for item in dex)
        packed = sum(item.compress_size for item in dex)
    print(f"DEX 压缩检查通过：{len(dex)} 个，{raw:,} → {packed:,} 字节；"
          f"APK {apk.stat().st_size:,} 字节")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()
    try:
        check(args.apk)
    except (OSError, ValueError, zipfile.BadZipFile, RuntimeError, zlib.error) as error:
        parser.exit(1, f"DEX 压缩检查失败：{error}\n")
