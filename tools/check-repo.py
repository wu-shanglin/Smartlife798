#!/usr/bin/env python3
"""Offline repository hygiene and lexical architecture checks; no app execution."""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = "com.smartlife798.app"
SOURCE = "SmartLife798/app/src/main/java/com/smartlife798/app/"
LINK = re.compile(r"!?\[[^\]\n]*\]\(<?([^\s)>]+)>?(?:\s+\"[^\"]*\")?\)")
REFERENCE = re.compile(r"^\s*\[[^\]\n]+\]:\s*<?([^\s>]+)>?", re.M)
GENERATED = {"build", ".gradle", ".kotlin", ".idea", "__pycache__"}
LOCAL = {"_extract", "jadx_src", "jadx_res", "dexmeta", "evidence", "dist"}
FORBIDDEN_SUFFIXES = {".apk", ".jks", ".keystore", ".pyc", ".hprof", ".log", ".iml", ".monitor"}


def repository_files(root: Path) -> list[str]:
    result = subprocess.run(
        ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
        cwd=root, check=True, capture_output=True,
    )
    return sorted(set(result.stdout.decode("utf-8").strip("\0").split("\0")) - {""})


def file_problems(name: str) -> list[str]:
    path = Path(name)
    parts = path.parts
    problems = []
    if (path.suffix.lower() in FORBIDDEN_SUFFIXES
            or path.name in {"local.properties", "keystore.properties"}
            or GENERATED.intersection(parts) or parts[0] in LOCAL
            or name.startswith(("tools/jadx/", "tools/alipay/", "tools/m3/", "tools/patches/"))
            or path.suffix.lower() in {".zip", ".aar"}):
        problems.append("禁止入库的本地配置、产物或快照")
    if name.startswith("screenshots/") and re.search(
        r"^(?:new_|old_)|(?:_v\d+|_old|_new)$", path.stem, re.I,
    ):
        problems.append("截图使用轮次命名，须同名覆盖")
    return problems


def kotlin_code(text: str) -> str:
    # Keep strings out of dependency checks: UI copy can legitimately mention Api.
    return re.sub(r'/\*.*?\*/|//[^\n]*|""".*?"""|"(?:\\.|[^"\\])*"', "", text, flags=re.S)


def architecture_problems(name: str, text: str) -> list[str]:
    if not name.startswith(SOURCE) or not name.endswith(".kt"):
        return []
    layer = name[len(SOURCE):].split("/")[0]
    code = kotlin_code(text)
    imports = re.findall(r"^\s*import\s+([\w.*]+)", code, re.M)
    problems = []
    if layer in {"core", "vm"} and any(i.startswith("androidx.compose.") for i in imports):
        problems.append(f"{layer} 不得依赖 Compose")
    blocked = ("ui", "vm") if layer == "core" else ("ui",) if layer == "vm" else ()
    if any(re.search(rf"\b{re.escape(PACKAGE)}\.{other}\.", code) for other in blocked):
        problems.append(f"{layer} 存在反向层依赖")
    if layer == "ui":
        aliases = re.findall(rf"^\s*import\s+{re.escape(PACKAGE)}\.core\.Api\s+as\s+(\w+)", code, re.M)
        body = re.sub(r"^\s*import[^\n]*", "", code, flags=re.M)
        receivers = "|".join(map(re.escape, ["Api", *aliases]))
        # Reading protocol constants for UI copy is allowed; invoking a method,
        # taking its reference, or alias-importing it is not.
        calls = re.search(rf"\b(?:{receivers})\s*(?:\.\s*\w+\s*\(|::)", body)
        method_import = any(i.startswith(f"{PACKAGE}.core.Api.") for i in imports)
        if calls or method_import:
            problems.append("UI 不得直接调用 Api")
        if (any(i.startswith(("okhttp3.", "java.net.", "javax.net.")) for i in imports)
                or re.search(r"\b(?:okhttp3|java\.net|javax\.net)\.", code)):
            problems.append("UI 不得直接创建网络客户端")
        if re.search(r"\b(?:getSharedPreferences|SharedPreferences|Store)\s*[.(]", code):
            problems.append("UI 不得直接访问持久化存储")
    return problems


def local_links(text: str) -> list[str]:
    # Code examples are documentation, not live links.
    prose = re.sub(r"```.*?```|`[^`\n]+`|<!--.*?-->", "", text, flags=re.S)
    return LINK.findall(prose) + REFERENCE.findall(prose)


def check(root: Path, names: list[str]) -> list[str]:
    failures = []
    referenced_images: set[Path] = set()
    for name in names:
        path = root / name
        if not path.is_file():
            continue  # Deleted tracked files remain in the index until staged.
        for problem in file_problems(name):
            failures.append(f"{name}: {problem}")
        if path.suffix not in {".md", ".kt", ".kts", ".properties"}:
            continue
        text = path.read_text(encoding="utf-8")
        failures.extend(f"{name}: {p}" for p in architecture_problems(name, text))
        if path.suffix in {".kts", ".properties"} and (
            re.search(r"[A-Za-z]:[\\/]", text)
            or re.search(r"^\s*org\.gradle\.java\.home\s*=", text, re.M)
        ):
            failures.append(f"{name}: 构建配置包含机器路径或固定 java.home")
        if path.suffix != ".md":
            continue
        for link in local_links(text):
            url = urlsplit(link)
            if url.scheme or url.netloc or not url.path:
                continue
            target = (path.parent / unquote(url.path)).resolve()
            if not target.is_relative_to(root.resolve()):
                failures.append(f"{name}: 相对链接越出仓库: {link}")
                continue
            # Local distribution APKs are intentionally not in a fresh checkout.
            if target.parent == root / "dist" and target.suffix == ".apk":
                continue
            if not target.exists():
                failures.append(f"{name}: 失效链接: {link}")
            if name != "CHANGELOG.md" and target.parent == root / "screenshots":
                referenced_images.add(target)
    for name in names:
        path = root / name
        if name.startswith("screenshots/") and path.is_file() and path.suffix in {".png", ".jpg", ".webp"}:
            if path.resolve() not in referenced_images:
                failures.append(f"{name}: 未被当前文档引用的截图")
    return failures


def main() -> int:
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    try:
        names = repository_files(ROOT)
        failures = check(ROOT, names)
    except (OSError, UnicodeError, subprocess.CalledProcessError, ValueError) as exc:
        print(f"[错误] 无法完成仓库检查: {exc}", file=sys.stderr)
        return 2
    for failure in failures:
        print(f"[失败] {failure}", file=sys.stderr)
    if failures:
        return 1
    print("仓库检查通过（文件、链接、截图引用、分层与机器路径）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
