#!/usr/bin/env python3
"""Move feature-level Card call sites onto the material-carrying AppCard.

Miuix Card accepts only one fill colour, so the rc10 card material (squircle curvature, 1dp rim,
themed depth shadow, optional top sheen) cannot live inside it. AppCard in the design system is a
signature-compatible wrapper; this script swaps the call sites over and fixes the imports.
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
MODULES = ["feature", "shared"]
IMPORT_OLD = "import top.yukonga.miuix.kmp.basic.Card\n"
IMPORT_APP = "import io.github.betterzslf.ui.AppCard\n"
DESIGN_SYSTEM_PKG = "package io.github.betterzslf.ui"


def main() -> int:
    changed: list[str] = []
    for module in MODULES:
        for path in sorted((ROOT / module).rglob("*.kt")):
            if "build" in path.parts or "Test" in path.name or "test" in str(path).lower():
                continue
            text = path.read_text(encoding="utf-8")
            if not re.search(r"(?<![\w.])Card\(", text):
                continue
            # A file inside the design system package already sees AppCard without an import.
            in_design_pkg = DESIGN_SYSTEM_PKG in text.splitlines()[0]
            new = re.sub(r"(?<![\w.])Card\(", "AppCard(", text)
            if new == text:
                continue
            if not in_design_pkg:
                if IMPORT_OLD in new:
                    new = new.replace(IMPORT_OLD, "")
                if IMPORT_APP not in new:
                    lines = new.split("\n")
                    insert_at = max(
                        (i for i, line in enumerate(lines) if line.startswith("import ")),
                        default=0,
                    ) + 1
                    lines.insert(insert_at, IMPORT_APP.rstrip("\n"))
                    new = "\n".join(lines)
            if not new.endswith("\n"):
                new += "\n"
            path.write_text(new, encoding="utf-8")
            changed.append(str(path.relative_to(ROOT)))
    for name in changed:
        print(name)
    print(f"-- {len(changed)} file(s) updated", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
