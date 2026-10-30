#!/usr/bin/env python3
"""Fail if a tracked source file carries CRLF line endings.

`.gitattributes` declares `* text=auto eol=lf`, but that only normalises content
git writes -- it does not stop an editor or a script from writing CRLF into the
working tree. Two real regressions came from exactly that: `display_text_guard`
regexes read files with `newline=''`, so CRLF made `^    object Run \\{\\n` stop
matching and seven Pythontests failed for a reason unrelated to the change.

Run from the repository root:
    python scripts/check_line_endings.py            # fail on CRLF
    python scripts/check_line_endings.py --fix      # rewrite as LF first
"""
from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

# Binary or intentionally platform-specific files are not line-ending checked.
SKIP_SUFFIXES = ('.png', '.jpg', '.jpeg', '.webp', '.gif', '.jar', '.jks', '.keystore',
                 '.p12', '.apk', '.aab', '.zip', '.bat', '.ps1')


def tracked_files(root: Path) -> list[Path]:
    out = subprocess.run(['git', 'ls-files', '-z'], cwd=root, capture_output=True, check=True)
    names = [name for name in out.stdout.decode('utf-8').split('\0') if name]
    return [root / name for name in names]


def dirty_files(root: Path) -> set[str]:
    """Paths git reports as modified or untracked.

    Pre-existing CRLF in an untouched working-tree file is invisible to git because
    `.gitattributes` normalises it, so flagging those would fail on noise the author
    never wrote. Only files this change actually touches are held to the standard.
    """
    out = subprocess.run(['git', 'status', '--porcelain', '-z', '--untracked-files=all'],
                         cwd=root, capture_output=True, check=True)
    entries = [e for e in out.stdout.decode('utf-8').split('\0') if e]
    return {entry[3:] for entry in entries if len(entry) > 3}


def has_crlf(path: Path) -> bool:
    try:
        return b'\r\n' in path.read_bytes()
    except OSError:
        return False


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--fix', action='store_true', help='rewrite offending files as LF')
    parser.add_argument('--all', action='store_true',
                        help='check every tracked file, including ones this change did not touch')
    args = parser.parse_args(argv)
    root = args.root.resolve()
    scope = tracked_files(root)
    if not args.all:
        dirty = dirty_files(root)
        scope = [p for p in scope if p.relative_to(root).as_posix() in dirty]
    offenders = [p for p in scope
                 if not p.name.endswith(SKIP_SUFFIXES) and p.is_file() and has_crlf(p)]
    if args.fix:
        for path in offenders:
            path.write_bytes(path.read_bytes().replace(b'\r\n', b'\n'))
            print(f'fixed: {path.relative_to(root).as_posix()}')
        return 0
    for path in offenders:
        print(f'CRLF: {path.relative_to(root).as_posix()}')
    print(f'line endings: {"FAIL" if offenders else "PASS"} ({len(offenders)} CRLF file(s))')
    return 1 if offenders else 0


if __name__ == '__main__':
    sys.exit(main())
