"""使用合成包验证来源证据不会把引用、截断或原生库偶然字节当成协议实现。"""

import hashlib
import io
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile
import zlib

from inspect_vendor_apks import TARGETS, descriptor, inspect_apk, inspect_dex, native_markers


def synthetic_dex(defined=False, referenced=True):
    strings = ("Lsample/Client;", descriptor(TARGETS[2]))
    types = (0, 1) if referenced else (0,)
    classes = (0, 1) if defined else (0,)
    data = bytearray(112 + len(strings) * 4 + len(types) * 4 + len(classes) * 32)
    string_offset = 112
    type_offset = string_offset + len(strings) * 4
    class_offset = type_offset + len(types) * 4
    for index, value in enumerate(strings):
        struct.pack_into("<I", data, string_offset + index * 4, len(data))
        data.extend(bytes((len(value),)) + value.encode("ascii") + b"\0")
    for index, value in enumerate(types):
        struct.pack_into("<I", data, type_offset + index * 4, value)
    for index, value in enumerate(classes):
        struct.pack_into("<I", data, class_offset + index * 32, value)
    data[:8] = b"dex\n035\0"
    struct.pack_into("<III", data, 32, len(data), 112, 0x12345678)
    for offset, count, start in ((56, len(strings), string_offset), (64, len(types), type_offset), (96, len(classes), class_offset)):
        struct.pack_into("<II", data, offset, count, start)
    return checksum(data)


def checksum(data):
    data[12:32] = hashlib.sha1(data[32:]).digest()
    struct.pack_into("<I", data, 8, zlib.adler32(data[12:]))
    return bytes(data)


def zipped(entries):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w") as archive:
        for name, data in entries.items():
            archive.writestr(name, data)
    return stream.getvalue()


class VendorEvidenceTest(unittest.TestCase):
    def report(self, entries):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "sample.apk"
            path.write_bytes(zipped(entries))
            return inspect_apk(path)

    def test_definition_is_distinct_from_external_type_reference(self):
        for defined, expected in ((True, "DEFINED"), (False, "TYPE_REFERENCE_ONLY")):
            with self.subTest(defined=defined):
                report = self.report({"classes.dex": synthetic_dex(defined)})
                self.assertEqual(expected, report["targets"][TARGETS[2]]["status"])
                self.assertEqual("UNTESTED", report["businessAuthorization"])

    def test_string_reference_is_not_a_definition(self):
        report = self.report({"classes.dex": synthetic_dex(referenced=False)})
        self.assertEqual("STRING_REFERENCE_ONLY", report["targets"][TARGETS[2]]["status"])

    def test_corruption_is_a_scan_gap_not_complete_absence(self):
        data = bytearray(synthetic_dex())
        data[-2] ^= 1
        report = self.report({"classes.dex": data})
        self.assertEqual("INCOMPLETE", report["scanStatus"])
        self.assertEqual("DEX_CHECKSUM_INVALID", report["gaps"][0]["reason"])

    def test_out_of_bounds_class_index_is_rejected(self):
        data = bytearray(synthetic_dex())
        class_offset = struct.unpack_from("<I", data, 100)[0]
        struct.pack_into("<I", data, class_offset, 4000)
        with self.assertRaisesRegex(ValueError, "DEX_CLASS_INDEX_INVALID"):
            inspect_dex(checksum(data))

    def test_truncation_and_future_container_do_not_claim_absence(self):
        for data in (b"dex\n035\0", b"dex\n041\0" + bytes(104)):
            with self.subTest(size=len(data)):
                report = self.report({"classes.dex": data})
                self.assertEqual("INCOMPLETE", report["scanStatus"])

    def test_nested_dex_is_located_without_extraction(self):
        report = self.report({"assets/sdk.jar": zipped({"classes.dex": synthetic_dex(True)})})
        self.assertEqual(["assets/sdk.jar!classes.dex"], report["targets"][TARGETS[2]]["definitions"])

    def test_jar_directory_entry_does_not_claim_dex_definition(self):
        entry = TARGETS[2].replace(".", "/") + ".class"
        report = self.report({"assets/sdk.jar": zipped({entry: b"unverified bytecode"})})
        self.assertEqual("JAR_ENTRY_UNVERIFIED", report["targets"][TARGETS[2]]["status"])
        self.assertEqual([], report["targets"][TARGETS[2]]["definitions"])

    def test_nested_limit_is_explicit(self):
        nested = zipped({"classes.dex": synthetic_dex(True)})
        for _ in range(3):
            nested = zipped({"sdk.jar": nested})
        report = self.report({"assets/sdk.jar": nested})
        self.assertEqual("INCOMPLETE", report["scanStatus"])
        self.assertEqual("NESTING_LIMIT", report["gaps"][0]["reason"])

    def test_native_accidental_marker_is_never_a_protocol(self):
        markers = native_markers(b"randomqnxbytes\0QNX\0")
        self.assertEqual(2, markers["qnx"]["byteOccurrences"])
        self.assertEqual(1, markers["qnx"]["boundedOccurrences"])
        self.assertEqual("MARKER_ONLY_NOT_PROTOCOL", markers["qnx"]["interpretation"])


if __name__ == "__main__":
    unittest.main()
