#!/usr/bin/env python3
"""离线定位厂商 SDK 定义、外部引用及原生库线索；不执行 APK 或输出任意字符串。"""

import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import struct
import zipfile
import zlib


TARGETS = (
    "ecarx.fw.api.ECarXAPI",
    "ecarx.fw.api.ICreator",
    "ecarx.fw.api.diminteraction.EcarxNaviInteraction",
    "com.ecarx.eas.sdk.mediacenter.MediaCenterAPI",
    "com.ecarx.eas.sdk.mediacenter.MusicClient",
    "com.ecarx.eas.sdk.mediacenter.MusicPlaybackInfo",
)
MARKERS = (b"qnx", b"mediacenter", b"diminteraction", b"turnbyturn", b"nextguidance")
MAX_ENTRY_BYTES = 512 * 1024 * 1024
MAX_ARCHIVE_BYTES = 1024 * 1024 * 1024
MAX_NESTING = 2


def uint(data, offset):
    if offset < 0 or offset + 4 > len(data):
        raise ValueError("DEX_OFFSET_OUT_OF_RANGE")
    return struct.unpack_from("<I", data, offset)[0]


def table(data, header_offset, stride):
    count, offset = uint(data, header_offset), uint(data, header_offset + 4)
    if count and (offset < 112 or offset + count * stride > len(data)):
        raise ValueError("DEX_TABLE_OUT_OF_RANGE")
    return [uint(data, offset + i * stride) for i in range(count)]


def dex_string(data, offset):
    # 类型描述符是 ASCII；其他 MUTF-8 字符串仅用于精确匹配，不写入报告。
    for i in range(5):
        if offset >= len(data):
            raise ValueError("DEX_STRING_OUT_OF_RANGE")
        value = data[offset]
        offset += 1
        if i == 4 and value > 15:
            raise ValueError("DEX_ULEB128_INVALID")
        if not value & 128:
            break
    else:
        raise ValueError("DEX_ULEB128_INVALID")
    end = data.find(b"\0", offset)
    if end < 0:
        raise ValueError("DEX_STRING_UNTERMINATED")
    return data[offset:end].decode("utf-8", errors="replace")


def inspect_dex(data):
    if len(data) < 112 or not re.fullmatch(rb"dex\n0(?:35|37|38|39|40)\x00", data[:8]):
        raise ValueError("DEX_VERSION_UNSUPPORTED_OR_INVALID")
    if uint(data, 32) != len(data) or uint(data, 36) != 112 or uint(data, 40) != 0x12345678:
        raise ValueError("DEX_HEADER_INVALID")
    if data[12:32] != hashlib.sha1(data[32:]).digest() or uint(data, 8) != zlib.adler32(data[12:]):
        raise ValueError("DEX_CHECKSUM_INVALID")
    strings = [dex_string(data, offset) for offset in table(data, 56, 4)]
    indices = table(data, 64, 4)
    if any(i >= len(strings) for i in indices):
        raise ValueError("DEX_TYPE_INDEX_INVALID")
    types = [strings[i] for i in indices]
    classes = table(data, 96, 32)
    if any(i >= len(types) for i in classes):
        raise ValueError("DEX_CLASS_INDEX_INVALID")
    return strings, set(types), {types[i] for i in classes}


def descriptor(name):
    return "L" + name.replace(".", "/") + ";"


def native_markers(data):
    lower = data.lower()
    return {
        marker.decode("ascii"): {
            "byteOccurrences": lower.count(marker),
            "boundedOccurrences": len(re.findall(rb"(?<![a-z0-9_])" + marker + rb"(?![a-z0-9_])", lower)),
            "interpretation": "MARKER_ONLY_NOT_PROTOCOL",
        }
        for marker in MARKERS if marker in lower
    }


def scan_entry(archive, entry, prefix, depth, result):
    name = prefix + entry.filename
    suffix = Path(entry.filename).suffix.lower()
    if suffix not in (".dex", ".so", ".jar", ".aar", ".apk", ".zip", ".class"):
        return
    if entry.file_size > MAX_ENTRY_BYTES:
        result["gaps"].append({"entry": name, "reason": "ENTRY_SIZE_LIMIT"})
        return
    if suffix == ".class":
        # 常规 JAR 类路径仅是目录证据，未验证 JVM 内部类名或可执行性。
        for target in TARGETS:
            if entry.filename == target.replace(".", "/") + ".class":
                result["targetJarEntries"][target].append(name)
        return
    data = archive.read(entry)
    if suffix == ".dex":
        strings, types, classes = inspect_dex(data)
        result["dex"].append({"entry": name, "classes": len(classes), "sha256": hashlib.sha256(data).hexdigest()})
        result["classes"].update(classes)
        string_set = set(strings)
        for target in TARGETS:
            if descriptor(target) in classes:
                result["targetDefinitions"][target].append(name)
            if descriptor(target) in types:
                result["targetTypes"][target].append(name)
            if descriptor(target) in string_set or target in string_set:
                result["targetStrings"][target].append(name)
    elif suffix == ".so":
        result["native"].append({"entry": name, "sha256": hashlib.sha256(data).hexdigest(), "markers": native_markers(data)})
    elif depth >= MAX_NESTING:
        result["gaps"].append({"entry": name, "reason": "NESTING_LIMIT"})
    else:
        with zipfile.ZipFile(io.BytesIO(data)) as nested:
            scan_archive(nested, name + "!", depth + 1, result)


def scan_archive(archive, prefix, depth, result):
    entries = archive.infolist()
    if sum(entry.file_size for entry in entries) > MAX_ARCHIVE_BYTES:
        result["gaps"].append({"entry": prefix, "reason": "ARCHIVE_SIZE_LIMIT"})
        return
    for entry in sorted(entries, key=lambda item: item.filename):
        if entry.is_dir():
            continue
        try:
            scan_entry(archive, entry, prefix, depth, result)
        except (ValueError, OSError, RuntimeError, zipfile.BadZipFile, NotImplementedError) as error:
            # 不输出库异常中的原始内容；失败不能被归类成目标不存在。
            reason = str(error) if type(error) is ValueError and str(error).startswith("DEX_") else type(error).__name__
            result["gaps"].append({"entry": prefix + entry.filename, "reason": reason})


def target_results(result):
    evidence = {}
    for target in TARGETS:
        definitions, types, strings = (result[key][target] for key in ("targetDefinitions", "targetTypes", "targetStrings"))
        jar_entries = result["targetJarEntries"][target]
        status = "DEFINED" if definitions else "TYPE_REFERENCE_ONLY" if types else "STRING_REFERENCE_ONLY" if strings else "JAR_ENTRY_UNVERIFIED" if jar_entries else "NOT_FOUND_IN_SCANNED_ENTRIES"
        evidence[target] = {"status": status, "definitions": definitions, "typeReferences": types,
                            "stringReferences": strings, "jarEntriesUnverified": jar_entries}
    return evidence


def inspect_apk(path):
    result = {"dex": [], "native": [], "classes": set(), "gaps": []}
    for key in ("targetDefinitions", "targetTypes", "targetStrings", "targetJarEntries"):
        result[key] = {target: [] for target in TARGETS}
    with zipfile.ZipFile(path) as archive:
        scan_archive(archive, "", 0, result)
    with path.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    candidates = sorted(name[1:-1].replace("/", ".") for name in result["classes"]
                        if "mediacenter" in name.lower() and name.endswith("Service;"))
    return {"apk": path.name, "sha256": digest,
            "scanStatus": "INCOMPLETE" if result["gaps"] else "COMPLETE_FOR_DECLARED_FORMATS",
            "definedClasses": len(result["classes"]), "targets": target_results(result),
            "mediaServiceClassCandidates": candidates, "dex": result["dex"], "native": result["native"],
            "gaps": result["gaps"], "businessAuthorization": "UNTESTED", "targetDisplay": "UNTESTED"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk_dir", type=Path)
    parser.add_argument("--output", type=Path, default=Path("build/vendor-reference/apk-evidence.json"))
    args = parser.parse_args()
    paths = sorted(args.apk_dir.glob("*.apk"))
    if not paths:
        parser.error("指定目录没有 APK")
    reports = [inspect_apk(path) for path in paths]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({"schema": 1, "scope": "OFFLINE_APK_ENTRIES_ONLY",
                                     "formatReference": "https://source.android.com/docs/core/runtime/dex-format",
                                     "apks": reports}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"已检查 {len(reports)} 个 APK；不完整扫描 {sum(bool(item['gaps']) for item in reports)} 个。报告：{args.output}")
    return 1 if any(item["gaps"] for item in reports) else 0


if __name__ == "__main__":
    raise SystemExit(main())
