#!/usr/bin/env python3
"""检查中文默认文案是否有英文资源，以及翻译是否保留格式占位符和选项数量。"""
from collections import Counter
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
HAN = re.compile(r"[\u3400-\u9fff]")
FORMAT = re.compile(r"%(?:\d+\$)?[\d.]*[dsf]")


def resources(*directories):
    result = {}
    for path in sorted(path for directory in directories for path in directory.glob("*.xml")):
        for item in ET.parse(path).getroot():
            if item.tag not in ("string", "string-array", "plurals"):
                continue
            key = (item.tag, item.get("name"))
            assert key not in result, f"资源重复：{path}: {key}"
            result[key] = item
    return result


def check():
    checked = 0
    for module in ("common", "mobile", "shared"):
        roots = ([ROOT / "shared/src/main/res"] if module == "shared" else
                 [ROOT / "galaxy" / module / "src/main/res"])
        english = resources(*(base / "values-en" for base in roots))
        for key, original in resources(*(base / "values" for base in roots)).items():
            source = "".join(original.itertext())
            if original.get("translatable") == "false" or not HAN.search(source):
                continue
            assert key in english, f"缺少英文：{module}: {key}"
            translated = english[key]
            value = "".join(translated.itertext())
            assert not HAN.search(value), f"英文资源含中文：{module}: {key}"
            assert len(original) == len(translated), f"选项数量不同：{module}: {key}"
            source_items = list(original) if len(original) else [original]
            target_items = list(translated) if len(translated) else [translated]
            for source_item, target_item in zip(source_items, target_items):
                assert Counter(FORMAT.findall("".join(source_item.itertext()))) == Counter(
                    FORMAT.findall("".join(target_item.itertext()))
                ), f"格式占位符不同：{module}: {key}"
            checked += 1
    for name in ("galaxyplay-first-use-agreement.en.md", "galaxyplay-third-party-notices.en.txt"):
        assert not HAN.search((ROOT / "galaxy/common/src/main/assets" / name).read_text()), f"英文正文含中文：{name}"
    print(f"英文资源检查通过：{checked} 项，含选项数组及格式占位符；英文正文无中文残留。")


if __name__ == "__main__":
    check()
