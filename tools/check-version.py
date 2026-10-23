#!/usr/bin/env python3
"""版本一致性校验（发版 / CI 必跑）。

业务规则（见 AGENTS.md "提交与版本"）：

  * 版本号的唯一真值来源是 `SmartLife798/app/build.gradle.kts`
    的 `versionName` / `versionCode`；
  * `CHANGELOG.md` 必须有该版本的段，且保留 `## [Unreleased]` 段；
  * `README.md` 的「当前版本」「产物」「产物 SHA-256」必须与真值一致；
  * `dist/SmartLife798-<versionName>-debug.apk` 若存在，其 SHA-256 必须与 README 一致；
  * `dist/` 里不允许残留其他版本的产物（避免发错包）。

用法：

    python tools/check-version.py            # 校验
    python tools/check-version.py --quiet    # 只输出失败项

退出码：0 通过 / 1 不一致 / 2 输入不可读。
"""

from __future__ import annotations

import argparse
import hashlib
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
    except OSError as exc:
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


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def check(quiet: bool) -> int:
    failures: list[str] = []
    notes: list[str] = []

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
    readme = norm(readme_raw)

    if f"|当前版本|{version}" not in readme or f"versionCode{code}" not in readme:
        failures.append(f"README.md 版本表未同步为 `{version}`（`versionCode {code}`）")

    apk_name = f"SmartLife798-{version}-debug.apk"
    if f"dist/{apk_name}" not in readme:
        failures.append(f"README.md 的产物链接不是 dist/{apk_name}")

    # ---- 产物 ------------------------------------------------------------
    # README 已被归一化（去反引号与空白），匹配 `|产物SHA-256|<64 hex>|`
    match = re.search(r"产物SHA-256\|([0-9a-f]{64})", readme)
    readme_hash = match.group(1) if match else None
    if not readme_hash:
        failures.append("README.md 缺少 64 位十六进制的「产物 SHA-256」")

    apk = DIST / apk_name
    if apk.exists():
        actual = sha256_file(apk)
        if readme_hash is None:
            pass  # 缺失已在上面报过，这里不重复
        elif actual != readme_hash:
            failures.append(
                f"README.md 的产物 SHA-256 与 dist/{apk_name} 不一致\n"
                f"  README: {readme_hash}\n"
                f"  实际  : {actual}"
            )
        elif not quiet:
            print(f"产物校验通过：dist/{apk_name}")
    else:
        notes.append(
            f"dist/{apk_name} 不存在（dist/ 不入库），跳过 SHA-256 比对；"
            "发版时必须重新生成并更新 README"
        )

    if DIST.is_dir():
        stale = sorted(
            p.name
            for p in DIST.glob("SmartLife798-*-debug.apk")
            if p.name != apk_name
        )
        if stale:
            failures.append(
                "dist/ 里有非当前版本的产物（容易发错包）：" + ", ".join(stale)
            )

    # ---- 输出 ------------------------------------------------------------
    for note in notes:
        print(f"[提示] {note}")
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
    args = parser.parse_args()
    try:
        return check(args.quiet)
    except Problem as exc:
        print(f"[错误] {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
