#!/usr/bin/env python3
"""Detect API drift between a new 慧生活798 APK and the maintained contract.

There is no public API documentation for this service, so the contract in
docs/api-contract.json records the full `/api/v1` path baseline of the analysed
build (v3.1.5) plus the endpoints this client actually uses. When a new APK is
released, run this script instead of re-running a full decompilation:

    python tools/api-diff.py "慧生活798-new.apk"
    python tools/api-diff.py "慧生活798-new.apk" --dump docs/api-paths.txt

It extracts every string constant from the DEX files (no decompiler, no code
execution) and reports:
  * paths added since the baseline            (new API surface)
  * paths removed since the baseline          (API removal / rename)
  * contract endpoints no longer present      (client breakage risk)
Exit code 1 means the contract needs review.
"""

import argparse
import json
import os
import struct
import sys
import zipfile

DEX_MAGIC = b"dex\n"


def _uleb128(buf, off):
    result = 0
    shift = 0
    while True:
        byte = buf[off]
        off += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, off
        shift += 7


def _mutf8(buf, off):
    units = []
    size = len(buf)
    while off < size:
        c = buf[off]
        if c == 0:
            break
        if c < 0x80:
            units.append(c)
            off += 1
        elif c & 0xE0 == 0xC0:
            units.append(((c & 0x1F) << 6) | (buf[off + 1] & 0x3F))
            off += 2
        elif c & 0xF0 == 0xE0:
            units.append(((c & 0x0F) << 12) | ((buf[off + 1] & 0x3F) << 6) | (buf[off + 2] & 0x3F))
            off += 3
        else:
            off += 1
    text = "".join(chr(u) for u in units)
    return text.encode("utf-16", "surrogatepass").decode("utf-16", "replace")


def dex_strings(blob):
    if blob[:4] != DEX_MAGIC:
        return []
    count, offset = struct.unpack_from("<II", blob, 56)
    strings = []
    for i in range(count):
        (data_off,) = struct.unpack_from("<I", blob, offset + 4 * i)
        _, pos = _uleb128(blob, data_off)
        strings.append(_mutf8(blob, pos))
    return strings


def apk_api_paths(apk_path):
    paths = set()
    with zipfile.ZipFile(apk_path) as zf:
        for name in zf.namelist():
            if not name.endswith(".dex"):
                continue
            for value in dex_strings(zf.read(name)):
                if value.startswith("/api/v1/") and len(value) < 200:
                    paths.add(value)
    return paths


def normalize(path):
    """Contract templates use `{type}`; compare on the literal prefix."""
    return path.split("{")[0]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("apk")
    parser.add_argument("--contract", default=os.path.join("docs", "api-contract.json"))
    parser.add_argument("--dump", default=None, help="write the APK path list to this file")
    parser.add_argument("--allow-drift", action="store_true")
    args = parser.parse_args()

    with open(args.contract, "r", encoding="utf-8") as handle:
        contract = json.load(handle)
    baseline = set(contract.get("baseline", {}).get("paths", []))
    used = [e["path"] for e in contract.get("endpoints", [])]

    apk_paths = apk_api_paths(args.apk)
    if not apk_paths:
        print("No /api/v1 paths found — is this a valid APK?", file=sys.stderr)
        return 2

    if args.dump:
        with open(args.dump, "w", encoding="utf-8") as handle:
            handle.write("\n".join(sorted(apk_paths)) + "\n")
        print(f"dumped {len(apk_paths)} paths to {args.dump}")

    added = sorted(p for p in apk_paths if p not in baseline)
    removed = sorted(p for p in baseline if p not in apk_paths)
    missing_used = [p for p in used if not any(normalize(a).startswith(normalize(p)) for a in apk_paths)]

    print(f"APK paths     : {len(apk_paths)}")
    print(f"Baseline paths: {len(baseline)}")
    print(f"Contract uses : {len(used)}")

    if added:
        print(f"\n[NEW] {len(added)} path(s) since baseline:")
        for p in added:
            print(f"  + {p}")
    if removed:
        print(f"\n[REMOVED] {len(removed)} baseline path(s) gone:")
        for p in removed:
            print(f"  - {p}")
    if missing_used:
        print(f"\n[BREAK] {len(missing_used)} client endpoint(s) no longer in the APK:")
        for p in missing_used:
            print(f"  ! {p}")
    if not (added or removed or missing_used):
        print("\nNo drift detected.")

    if (added or removed or missing_used) and not args.allow_drift:
        print("\nReview docs/api-contract.json and the client before shipping.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
