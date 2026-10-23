"""Extract the reference APK into a reproducible local workspace."""
import argparse
import os
import sys
from pathlib import Path
import zipfile

root = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description="Extract APK members with Windows-safe collision names")
parser.add_argument("apk", nargs="?", type=Path, default=root / "慧生活798.apk")
parser.add_argument("--output", type=Path, default=root / "_extract")
args = parser.parse_args()
apk = args.apk
out = args.output.resolve()
os.makedirs(out, exist_ok=True)
zf = zipfile.ZipFile(apk)
infos = zf.infolist()
print("entries:", len(infos))
used = {}
collisions = 0
total = 0
errors = 0
for i in infos:
    name = i.filename
    safe = name.replace("\\", "/")
    if safe.endswith("/"):
        continue
    parts = []
    for p in safe.split("/"):
        if p in ("", "."):
            continue
        if p == "..":
            p = "__up__"
        parts.append(p)
    rel = "/".join(parts)
    key = rel.lower()
    while key in used:
        collisions += 1
        rel = rel + ("_dup%d" % collisions)
        key = rel.lower()
    used[key] = True
    dest = os.path.join(out, rel.replace("/", os.sep))
    resolved = Path(dest).resolve()
    if not resolved.is_relative_to(out):
        raise SystemExit("unsafe APK member path")
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    try:
        with zf.open(i) as src, open(dest, "wb") as dst:
            while True:
                b = src.read(1 << 20)
                if not b: break
                dst.write(b)
        total += 1
    except Exception as e:
        errors += 1
        print("ERR", name, e, file=sys.stderr)
print("extracted:", total, "collisions:", collisions)
if errors:
    sys.exit(1)
