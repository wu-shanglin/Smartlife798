#!/usr/bin/env python3
"""Keep only production classes from an ART human-readable profile dump.

Capture a non-debuggable, non-R8 offline test journey first (docs/06). Never
commit the raw dump, which also contains dependencies and instrumentation.
"""
import argparse
from pathlib import Path
import re


def production_rules(profile: str, classes: Path) -> list[str]:
    owned = {
        "L" + file.relative_to(classes).as_posix()[:-6] + ";"
        for file in classes.rglob("*.class")
    }
    if not owned:
        raise ValueError("No compiled production classes; build performance first")
    rules = set()
    for line in profile.splitlines():
        line = line.strip()
        match = re.match(r"^[HSP]*(L[^;]+;)(?:->[^\s]+)?$", line)
        if match and match[1].startswith("Lcom/smartlife798/app/") and match[1] in owned:
            rules.add(line)
    if not rules:
        raise ValueError("Profile has no matching production rules")
    return sorted(rules)


def main() -> None:
    root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("profile", type=Path)
    parser.add_argument("--classes", type=Path,
                        default=root / "SmartLife798/app/build/tmp/kotlin-classes/performance")
    parser.add_argument("--output", type=Path,
                        default=root / "SmartLife798/app/src/main/baseline-prof.txt")
    args = parser.parse_args()
    rules = production_rules(args.profile.read_text(encoding="utf-8"), args.classes)
    args.output.write_bytes(("\n".join(rules) + "\n").encode("utf-8"))
    print(f"Wrote {len(rules)} production profile rules; test/dependency classes excluded")


if __name__ == "__main__":
    main()
