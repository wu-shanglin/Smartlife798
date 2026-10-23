#!/usr/bin/env python3
"""Generate the public GitHub Release update.json from the Gradle version truth."""
import argparse
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def release_history(changelog: str, latest: str, notes: str) -> list[dict]:
    def version_key(name):
        return tuple(map(int, name.split('.')))

    sections = re.split(r'^## \[(\d+\.\d+\.\d+)\][^\n]*\n', changelog, flags=re.M)
    history = {latest: notes}
    seen = set()
    for name, body in zip(sections[1::2], sections[2::2]):
        if not re.fullmatch(r'(0|[1-9][0-9]{0,4})\.(0|[1-9][0-9]{0,4})\.(0|[1-9][0-9]{0,4})', name) or name in seen:
            raise ValueError('Invalid or duplicate release in changelog')
        seen.add(name)
        if name == latest or version_key(name) > version_key(latest):
            continue
        history[name] = body.strip()
    if len(history) > 256 or any(not body.strip() or len(body) > 4000 for body in history.values()):
        raise ValueError('Release history missing notes or exceeding limits')
    return [dict(versionName=name, notes=history[name]) for name in sorted(history, key=version_key, reverse=True)]


def metadata(gradle: str, minimum: int, notes: str, sha256: str) -> dict:
    code = int(re.search(r'versionCode\s*=\s*(\d+)', gradle).group(1))
    name = re.search(r'versionName\s*=\s*"([^"]+)"', gradle).group(1)
    if not 1 <= minimum <= code or not re.fullmatch(r'(0|[1-9][0-9]{0,4})\.(0|[1-9][0-9]{0,4})\.(0|[1-9][0-9]{0,4})', name):
        raise ValueError('Invalid version or minimum supported versionCode')
    if len(notes) > 4000:
        raise ValueError('Release notes exceed 4000 characters')
    if not re.fullmatch(r'[0-9a-f]{64}', sha256):
        raise ValueError('Invalid SHA-256')
    data = dict(versionCode=code, versionName=name, minSupportedVersionCode=minimum, notes=notes,
                sha256=sha256, historyAvailable=True)
    if len((json.dumps(data, ensure_ascii=False, indent=2) + '\n').encode('utf-8')) > 16_384:
        raise ValueError('Update metadata exceeds legacy response limit')
    return data


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
        history = dict(versionName=data['versionName'], releases=release_history(
            (ROOT / 'CHANGELOG.md').read_text(encoding='utf-8'), data['versionName'], data['notes']))
        history_json = json.dumps(history, ensure_ascii=False, indent=2) + '\n'
        if len(history_json.encode('utf-8')) > 1_048_576:
            raise ValueError('Release history exceeds response limit')
    except (ValueError, OSError) as error:
        parser.error(str(error))
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.with_name('release-history.json').write_text(history_json, encoding='utf-8', newline='\n')
    output.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(f'Generated update metadata for {data["versionName"]} (minimum {data["minSupportedVersionCode"]})')


if __name__ == '__main__':
    main()
