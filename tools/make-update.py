#!/usr/bin/env python3
"""Generate the public GitHub Release update.json from the Gradle version truth."""
import argparse
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def metadata(gradle: str, minimum: int, notes: str, sha256: str) -> dict:
    code = int(re.search(r'versionCode\s*=\s*(\d+)', gradle).group(1))
    name = re.search(r'versionName\s*=\s*"([^"]+)"', gradle).group(1)
    if not 1 <= minimum <= code or not re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)', name):
        raise ValueError('Invalid version or minimum supported versionCode')
    if len(notes) > 4000:
        raise ValueError('Release notes exceed 4000 characters')
    if not re.fullmatch(r'[0-9a-f]{64}', sha256):
        raise ValueError('Invalid SHA-256')
    return dict(versionCode=code, versionName=name, minSupportedVersionCode=minimum, notes=notes, sha256=sha256)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--min-supported-version-code', type=int, required=True,
                        help='1 for normal updates; raise only to retire affected older builds')
    parser.add_argument('--notes', required=True, help='Public UTF-8 release notes file; no account data')
    parser.add_argument('--apk', required=True, help='Verified APK to publish with update.json')
    parser.add_argument('--output', default=str(ROOT / 'dist' / 'update.json'))
    args = parser.parse_args()
    try:
        with Path(args.apk).open('rb') as apk:
            digest = hashlib.sha256()
            for chunk in iter(lambda: apk.read(1024 * 1024), b''):
                digest.update(chunk)
        data = metadata((ROOT / 'SmartLife798/app/build.gradle.kts').read_text(encoding='utf-8'),
                        args.min_supported_version_code, Path(args.notes).read_text(encoding='utf-8'),
                        digest.hexdigest())
    except (ValueError, OSError) as error:
        parser.error(str(error))
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(f'Generated update metadata for {data["versionName"]} (minimum {data["minSupportedVersionCode"]})')


if __name__ == '__main__':
    main()
