#!/usr/bin/env python3
"""版本一致性校验（发版 / CI 必跑）。

业务规则（见 docs/06-工程规范与路线图.md 的版本与发布）：

  * 版本号的唯一真值来源是 `SmartLife798/app/build.gradle.kts`
    的 `versionName` / `versionCode`；
  * `CHANGELOG.md` 必须有该版本的段，且保留 `## [Unreleased]` 段；
  * `README.md` 的「当前版本」「产物」必须与真值一致；
  * 仅 `--require-apk` 检查目标 APK 文件存在；不读取内容、不核对哈希。

用法：

    python tools/check-version.py            # 校验
    python tools/check-version.py --quiet    # 只输出失败项
    python tools/check-version.py --require-apk  # 发布准备：检查目标产物文件存在

退出码：0 通过 / 1 不一致 / 2 输入不可读。
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GRADLE = ROOT / "SmartLife798" / "app" / "build.gradle.kts"
CHANGELOG = ROOT / "CHANGELOG.md"
README = ROOT / "README.md"
DIST = ROOT / "dist"

# 中文输出统一走 UTF-8，避免 Windows 控制台代码页把日志变成乱码。
for stream in (sys.stdout, sys.stderr):
    reconfigure = getattr(stream, "reconfigure", None)
    if reconfigure is not None:
        try:
            reconfigure(encoding="utf-8")
        except (ValueError, OSError):
            pass


class Problem(Exception):
    """Input that cannot be validated at all (exit code 2)."""


def read(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except FileNotFoundError as exc:
        raise Problem(f"找不到文件：{path.relative_to(ROOT)}") from exc
    except (OSError, UnicodeError) as exc:
        raise Problem(f"无法读取 {path.relative_to(ROOT)}：{exc}") from exc


def parse_gradle(text: str) -> tuple[str, int]:
    name = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    code = re.search(r"versionCode\s*=\s*(\d+)", text)
    if not name or not code:
        raise Problem("app/build.gradle.kts 里没有找到 versionCode / versionName")
    return name.group(1), int(code.group(1))


def norm(text: str) -> str:
    """README / CHANGELOG 里的中文与反引号排版不影响比对。"""
    return re.sub(r"[`\s]+", "", text)


def table_value(text: str, label: str) -> str:
    """Only accept one complete two-column row, not a matching historical mention."""
    rows = re.findall(rf"^\s*\|\s*{re.escape(label)}\s*\|([^\n|]*)\|\s*$", text, re.M)
    return norm(rows[0]) if len(rows) == 1 else ""


def check(quiet: bool, require_apk: bool = False) -> int:
    failures: list[str] = []

    version, code = parse_gradle(read(GRADLE))
    if not quiet:
        print(f"真值：versionName={version} versionCode={code}（app/build.gradle.kts）")

    # ---- CHANGELOG -------------------------------------------------------
    changelog_raw = read(CHANGELOG)
    changelog = norm(changelog_raw)
    if f"##[{version}]" not in changelog:
        failures.append(f"CHANGELOG.md 缺少 `## [{version}] - YYYY-MM-DD` 段")
    if "##[Unreleased]" not in changelog:
        failures.append("CHANGELOG.md 缺少 `## [Unreleased]` 段")
    # 版本段必须有日期，否则无法回溯发版时间
    dated = re.search(rf"^##\s*\[{re.escape(version)}\]\s*-\s*\d{{4}}-\d{{2}}-\d{{2}}", changelog_raw, re.M)
    if f"##[{version}]" in changelog and not dated:
        failures.append(f"CHANGELOG.md 的 `## [{version}]` 标题缺少 `- YYYY-MM-DD` 日期")

    # ---- README ----------------------------------------------------------
    readme_raw = read(README)
    current = table_value(readme_raw, "当前版本")
    if current != f"{version}（versionCode{code}）":
        failures.append(f"README.md 版本表未同步为 `{version}`（`versionCode {code}`）")

    apk_name = f"SmartLife798-{version}-debug.apk"
    artifact = table_value(readme_raw, "产物")
    if not re.fullmatch(rf"\[[^\]]+\]\(dist/{re.escape(apk_name)}\)", artifact):
        failures.append(f"README.md 的产物链接不是 dist/{apk_name}")

    # 日常源码检查不依赖本地产物；发布准备仅确认目标文件存在。
    if require_apk and not (DIST / apk_name).is_file():
        failures.append(f"发布检查要求存在 APK 文件 dist/{apk_name}")

    if failures:
        for item in failures:
            print(f"[失败] {item}", file=sys.stderr)
        print(f"\n共 {len(failures)} 处版本信息不一致，请修正后重试。", file=sys.stderr)
        return 1
    if not quiet:
        print("版本信息一致。")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="校验版本号与 CHANGELOG / README / dist 的一致性")
    parser.add_argument("--quiet", action="store_true", help="只输出失败项")
    parser.add_argument("--require-apk", action="store_true", help="发布准备时要求目标 APK 文件存在（不核对哈希）")
    args = parser.parse_args()
    try:
        return check(args.quiet, args.require_apk)
    except Problem as exc:
        print(f"[错误] {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
