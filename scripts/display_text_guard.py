#!/usr/bin/env python3
"""Fail-closed, stdlib-only Kotlin literal guard for Phase A UI/App sources.

Public generator API:
    scan_source(source) -> list[Token]  # all tokens, including descendants
    has_han(token) -> bool            # decoded literal parts only
    make_exception(path, source, token, reason, owner, *, category='protocol')

Offsets are Python string offsets, end-exclusive; line/column are one-based.
Token.source includes delimiters. Literal parts have one segment per template
boundary (including empty segments); template expressions are never evaluated.
Children are immediate nested tokens, also present in the flat return value.
Read source with open(..., encoding='utf-8', newline='') to preserve CRLF.

Allowlist document: {"version": 1, "exceptions": [make_exception(...), ...]}.
Only protocol/lookup categories are accepted. An entry must uniquely match its
exact token and 48-character before/after context AND retain all four position
fields. Moved, stale, duplicate, ambiguous and non-Han entries fail validation.
This is a lexical scanner, not a Kotlin type checker or full expression parser.
Unsupported lexical forms (including multi-dollar string prefixes) raise
LexicalError; no Kotlin, callbacks or template expressions are executed.
"""
from __future__ import annotations

import argparse
from bisect import bisect_right
from dataclasses import dataclass
import json
from pathlib import Path, PurePosixPath
import sys
import unicodedata
from typing import Sequence


CONTEXT_CHARS = 48
KOTLIN_BASE = "shared/src/commonMain/kotlin/io/github/betterzslf/"
UI_PREFIX = KOTLIN_BASE + "ui/"
APP_PATH = KOTLIN_BASE + "App.kt"
TOKEN_KINDS = frozenset(("string", "raw_string", "char"))


@dataclass(frozen=True)
class TemplateHole:
    kind: str
    start: int
    end: int
    expression_start: int
    expression_end: int
    expression_source: str
    children: tuple[Token, ...] = ()


@dataclass(frozen=True)
class Token:
    kind: str
    start: int
    end: int
    line: int
    column: int
    source: str
    literal_parts: tuple[str, ...]
    holes: tuple[TemplateHole, ...] = ()
    children: tuple[Token, ...] = ()

    @property
    def raw_source(self) -> str:
        return self.source

    @property
    def decoded_literal(self) -> str:
        return "".join(self.literal_parts)


class LexicalError(ValueError):
    def __init__(self, message: str, source: str, offset: int):
        self.offset = offset
        self.line, self.column = _location(_line_starts(source), offset)
        self.message = message
        super().__init__(f"{message} at {self.line}:{self.column} (offset {offset})")


def _line_starts(source: str) -> list[int]:
    starts = [0]
    for i, char in enumerate(source):
        if char == "\n" or (char == "\r" and source[i + 1:i + 2] != "\n"):
            starts.append(i + 1)
    return starts


def _location(starts: list[int], offset: int) -> tuple[int, int]:
    line_index = bisect_right(starts, offset) - 1
    return line_index + 1, offset - starts[line_index] + 1


def _identifier_start(char: str) -> bool:
    return bool(char) and (char == "_" or unicodedata.category(char) in ("Lu", "Ll", "Lt", "Lm", "Lo"))


def _identifier_part(char: str) -> bool:
    return _identifier_start(char) or (bool(char) and unicodedata.category(char) == "Nd")


def _combine_surrogates(text: str) -> str:
    # Kotlin \uXXXX escapes describe UTF-16 code units, including surrogate pairs.
    out = []
    i = 0
    while i < len(text):
        high = ord(text[i])
        if 0xD800 <= high <= 0xDBFF and i + 1 < len(text):
            low = ord(text[i + 1])
            if 0xDC00 <= low <= 0xDFFF:
                out.append(chr(0x10000 + ((high - 0xD800) << 10) + low - 0xDC00))
                i += 2
                continue
        out.append(text[i])
        i += 1
    return "".join(out)


class _Scanner:
    def __init__(self, source: str):
        self.source = source
        self.size = len(source)
        self.lines = _line_starts(source)

    def fail(self, message: str, offset: int):
        raise LexicalError(message, self.source, offset)

    def block_comment(self, start: int) -> int:
        depth, i = 1, start + 2
        while i < self.size:
            if self.source.startswith("/*", i):
                depth += 1
                i += 2
            elif self.source.startswith("*/", i):
                depth -= 1
                i += 2
                if not depth:
                    return i
            else:
                i += 1
        self.fail("Unterminated block comment", start)

    def line_comment(self, start: int) -> int:
        i = start + 2
        while i < self.size and self.source[i] not in "\r\n":
            i += 1
        return i

    def code(self, start: int = 0, *, template: bool = False, depth: int = 0):
        tokens = []
        stack = []
        i, saw_code = start, False
        source = self.source
        pairs = {")": "(", "]": "[", "}": "{"}
        while i < self.size:
            char = source[i]
            if char in " \t\r\n\f" or (i == 0 and char == "\ufeff"):
                i += 1
                continue
            if source.startswith("//", i):
                i = self.line_comment(i)
                continue
            if source.startswith("/*", i):
                i = self.block_comment(i)
                continue
            if source.startswith("*/", i):
                self.fail("Unexpected block-comment terminator", i)
            if (i == 0 or (i == 1 and source[0] == "\ufeff")) and source.startswith("#!", i):
                i = self.line_comment(i)
                continue
            if char == "}" and template and not stack:
                if not saw_code:
                    self.fail("Empty template expression", start)
                return tokens, i + 1
            saw_code = True
            if char in "\"'":
                token = self.literal(i, depth)
                tokens.append(token)
                i = token.end
                continue
            if char == "`":
                end = source.find("`", i + 1)
                if end < 0 or "\n" in source[i + 1:end] or "\r" in source[i + 1:end]:
                    self.fail("Unterminated quoted identifier", i)
                if end == i + 1:
                    self.fail("Empty quoted identifier", i)
                i = end + 1
                continue
            if char == "$":
                self.fail("Unsupported dollar syntax or multi-dollar string prefix", i)
            if _identifier_start(char):
                i += 1
                while i < self.size and _identifier_part(source[i]):
                    i += 1
                continue
            if "0" <= char <= "9":
                i += 1
                continue
            if char in "({[":
                stack.append((char, i))
            elif char in ")}]":
                if not stack or stack[-1][0] != pairs[char]:
                    self.fail("Mismatched code delimiter", i)
                stack.pop()
            elif char not in "+-*/%=!<>?:;.,@&|":
                self.fail(f"Unsupported Kotlin character U+{ord(char):04X}", i)
            i += 1
        if stack:
            self.fail("Unterminated code delimiter", stack[-1][1])
        if template:
            self.fail("Unterminated template expression", start - 2)
        return tokens, i

    def escape(self, start: int) -> tuple[str, int]:
        if start + 1 >= self.size:
            self.fail("Unterminated escape", start)
        char = self.source[start + 1]
        escapes = {"t": "\t", "b": "\b", "n": "\n", "r": "\r", "'": "'", '"': '"', "\\": "\\", "$": "$"}
        if char in escapes:
            return escapes[char], start + 2
        if char == "u":
            digits = self.source[start + 2:start + 6]
            if len(digits) != 4 or any(ch not in "0123456789abcdefABCDEF" for ch in digits):
                self.fail("Invalid Unicode escape (expected four hex digits)", start)
            return chr(int(digits, 16)), start + 6
        self.fail("Unsupported Kotlin escape", start)

    def literal(self, start: int, depth: int) -> Token:
        if depth >= 100:
            self.fail("Supported template nesting depth exceeded", start)
        source = self.source
        char_literal = source[start] == "'"
        raw = not char_literal and source.startswith('"""', start)
        kind = "char" if char_literal else ("raw_string" if raw else "string")
        i = start + (3 if raw else 1)
        parts, chunk, holes, children = [], [], [], []
        while i < self.size:
            char = source[i]
            if raw and source.startswith('"""', i):
                end = i + 3
                while end < self.size and source[end] == '"':
                    end += 1
                # Extra closing-run quotes belong to the literal before its last 3.
                chunk.append('"' * (end - i - 3))
                break
            if not raw and char == ("'" if char_literal else '"'):
                end = i + 1
                break
            if not raw and char in "\r\n":
                self.fail("Newline in quoted literal", i)
            if not raw and char == "\\":
                decoded, i = self.escape(i)
                chunk.append(decoded)
                continue
            if char == "$" and not char_literal:
                next_char = source[i + 1:i + 2]
                if next_char == "{":
                    parts.append(_combine_surrogates("".join(chunk)))
                    chunk = []
                    expression_start = i + 2
                    nested, hole_end = self.code(expression_start, template=True, depth=depth + 1)
                    holes.append(TemplateHole("expression", i, hole_end, expression_start,
                                              hole_end - 1, source[expression_start:hole_end - 1], tuple(nested)))
                    children.extend(nested)
                    i = hole_end
                    continue
                if _identifier_start(next_char):
                    parts.append(_combine_surrogates("".join(chunk)))
                    chunk = []
                    end_ident = i + 2
                    while end_ident < self.size and _identifier_part(source[end_ident]):
                        end_ident += 1
                    holes.append(TemplateHole("identifier", i, end_ident, i + 1, end_ident,
                                              source[i + 1:end_ident]))
                    i = end_ident
                    continue
                if next_char == "`":
                    self.fail("Unsupported backtick short-template syntax; use ${...}", i)
            chunk.append(char)
            i += 1
        else:
            self.fail("Unterminated literal", start)
        parts.append(_combine_surrogates("".join(chunk)))
        if char_literal and (len(parts[0]) != 1 or ord(parts[0]) > 0xFFFF):
            self.fail("Char literal must decode to one UTF-16 code unit", start)
        line, column = _location(self.lines, start)
        return Token(kind, start, end, line, column, source[start:end], tuple(parts), tuple(holes), tuple(children))


def scan_source(source: str) -> list[Token]:
    """Return every string/char token, parents before children, in start order."""
    if not isinstance(source, str):
        raise TypeError("source must be str")
    scanner = _Scanner(source)
    roots, _ = scanner.code()
    result = []

    def visit(token: Token):
        result.append(token)
        for child in token.children:
            visit(child)

    for root in roots:
        visit(root)
    return result


# Unified/compatibility ideographs (including supplementary extensions), Han
# radicals and iteration/numeral marks. Explicit ranges avoid Unicode-version
# dependent blind spots in unicodedata for newly assigned extension blocks.
_HAN_RANGES = (
    (0x2E80, 0x2EF3), (0x2F00, 0x2FD5), (0x3005, 0x3005), (0x3007, 0x3007),
    (0x3021, 0x3029), (0x3038, 0x303B), (0x3400, 0x4DBF), (0x4E00, 0x9FFF),
    (0xF900, 0xFAFF), (0x16FE2, 0x16FE3), (0x16FF0, 0x16FF1),
    (0x20000, 0x2A6DF), (0x2A700, 0x2B73F), (0x2B740, 0x2B81F),
    (0x2B820, 0x2CEAF), (0x2CEB0, 0x2EBEF), (0x2EBF0, 0x2EE5F),
    (0x2F800, 0x2FA1F), (0x30000, 0x3134F), (0x31350, 0x323AF),
    (0x323B0, 0x3347F),
)


def has_han(token: Token) -> bool:
    """Inspect this token's decoded literal parts, never its expression source."""
    return any(low <= ord(char) <= high
               for part in token.literal_parts for char in part for low, high in _HAN_RANGES)


def _normal_path(path) -> str:
    value = str(path).replace("\\", "/")
    pure = PurePosixPath(value)
    if (not value or pure.is_absolute() or ":" in value or ".." in pure.parts
            or str(pure) != value or value.endswith("/")):
        raise ValueError("Expected canonical repository-relative path")
    if not (value == APP_PATH or (value.startswith(UI_PREFIX) and value.endswith(".kt"))):
        raise ValueError("Exception path is outside Phase A UI/App scope")
    return value


def _context(source: str, token: Token) -> dict:
    return {"before": source[max(0, token.start - CONTEXT_CHARS):token.start],
            "after": source[token.end:token.end + CONTEXT_CHARS]}


def make_exception(path, source: str, token: Token, reason: str, owner: str, *, category: str = "protocol") -> dict:
    """Build an exact-token exception; write these into version-1 exceptions."""
    path = _normal_path(path)
    if not (0 <= token.start < token.end <= len(source)) or source[token.start:token.end] != token.source:
        raise ValueError("Token does not belong to this exact source")
    if _location(_line_starts(source), token.start) != (token.line, token.column):
        raise ValueError("Token position does not belong to this exact source")
    if not has_han(token):
        raise ValueError("Only Han-bearing tokens require exceptions")
    entry = {"path": path, "kind": token.kind, "source": token.source,
             "context": _context(source, token),
             "position": {"start": token.start, "end": token.end, "line": token.line, "column": token.column},
             "category": category, "reason": reason, "owner": owner}
    _validate_entry(entry)
    return entry


def _validate_entry(entry) -> None:
    fields = {"path", "kind", "source", "context", "position", "category", "reason", "owner"}
    if not isinstance(entry, dict) or set(entry) != fields:
        raise ValueError("Exception requires exactly path/kind/source/context/position/category/reason/owner")
    if not isinstance(entry["path"], str) or _normal_path(entry["path"]) != entry["path"]:
        raise ValueError("Exception path must use canonical POSIX spelling")
    if not isinstance(entry["kind"], str) or entry["kind"] not in TOKEN_KINDS:
        raise ValueError("Unsupported exception token kind")
    for name in ("source", "reason", "owner"):
        if not isinstance(entry[name], str) or not entry[name].strip():
            raise ValueError(f"Exception {name} must be nonempty text")
    if entry["category"] not in ("protocol", "lookup"):
        raise ValueError("Exception category must be protocol or lookup")
    context = entry["context"]
    if not isinstance(context, dict) or set(context) != {"before", "after"}:
        raise ValueError("Exception requires exact before/after context")
    if any(not isinstance(context[key], str) or len(context[key]) > CONTEXT_CHARS for key in context):
        raise ValueError("Exception context must contain at most 48 characters per side")
    position = entry["position"]
    if not isinstance(position, dict) or set(position) != {"start", "end", "line", "column"}:
        raise ValueError("Exception requires exact start/end/line/column")
    if any(type(position[key]) is not int for key in position):
        raise ValueError("Exception positions must be integers")
    if not (0 <= position["start"] < position["end"] and position["line"] >= 1 and position["column"] >= 1):
        raise ValueError("Invalid exception positions")


def _json_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def load_allowlist(path: Path) -> list[dict]:
    with path.open("r", encoding="utf-8", newline="") as stream:
        document = json.load(stream, object_pairs_hook=_json_object)
    if not isinstance(document, dict) or set(document) != {"version", "exceptions"}:
        raise ValueError("Allowlist requires exactly version and exceptions")
    if type(document["version"]) is not int or document["version"] != 1:
        raise ValueError("Unsupported allowlist version")
    entries = document["exceptions"]
    if not isinstance(entries, list):
        raise ValueError("exceptions must be a list")
    for index, entry in enumerate(entries):
        try:
            _validate_entry(entry)
        except (ValueError, TypeError) as exc:
            raise ValueError(f"Exception {index}: {exc}") from exc
    return entries


@dataclass(frozen=True)
class GuardResult:
    files_scanned: int
    tokens_scanned: int
    han_tokens: int
    exempted_tokens: int
    violations: tuple[dict, ...]
    errors: tuple[dict, ...]

    @property
    def ok(self) -> bool:
        return not self.violations and not self.errors

    def to_dict(self) -> dict:
        return {"ok": self.ok, "files_scanned": self.files_scanned,
                "tokens_scanned": self.tokens_scanned, "han_tokens": self.han_tokens,
                "exempted_tokens": self.exempted_tokens,
                "violations": list(self.violations), "errors": list(self.errors)}


def run_guard(root, allowlist=None) -> GuardResult:
    """Scan only commonMain UI/**/*.kt plus its sibling App.kt."""
    root = Path(root).resolve()
    allowlist_path = Path(allowlist) if allowlist is not None else Path("scripts/display-text-exceptions.json")
    if not allowlist_path.is_absolute():
        allowlist_path = root / allowlist_path
    errors, scanned = [], {}
    try:
        entries = load_allowlist(allowlist_path)
    except (OSError, ValueError, TypeError) as exc:
        entries = []
        errors.append({"code": "allowlist_error", "message": str(exc)})
    ui = root / UI_PREFIX
    app = root / APP_PATH
    targets = []
    if not ui.is_dir():
        errors.append({"code": "scope_error", "path": UI_PREFIX, "message": "Missing Phase A UI directory"})
    else:
        targets.extend(sorted(ui.rglob("*.kt")))
    if not app.is_file():
        errors.append({"code": "scope_error", "path": APP_PATH, "message": "Missing Phase A App.kt"})
    else:
        targets.append(app)
    files_scanned = 0
    for path in targets:
        relative = path.relative_to(root).as_posix()
        try:
            if not path.resolve().is_relative_to(root):
                raise ValueError("Scoped source resolves outside repository")
            with path.open("r", encoding="utf-8", newline="") as stream:
                source = stream.read()
            tokens = scan_source(source)
            scanned[relative] = (source, tokens)
            files_scanned += 1
        except LexicalError as exc:
            errors.append({"code": "lexical_error", "path": relative, "message": exc.message,
                           "offset": exc.offset, "line": exc.line, "column": exc.column})
        except (OSError, ValueError) as exc:
            errors.append({"code": "source_error", "path": relative, "message": str(exc)})
    exempted = set()
    for index, entry in enumerate(entries):
        path = entry["path"]
        source, tokens = scanned.get(path, ("", []))
        matches = [token for token in tokens if token.kind == entry["kind"] and token.source == entry["source"]
                   and _context(source, token) == entry["context"]]
        if len(matches) != 1:
            errors.append({"code": "exception_match_error", "entry": index, "path": path,
                           "message": f"Expected one exact contextual token, found {len(matches)}"})
            continue
        token = matches[0]
        position = {"start": token.start, "end": token.end, "line": token.line, "column": token.column}
        key = (path, token.start, token.end)
        if position != entry["position"]:
            errors.append({"code": "exception_position_error", "entry": index, "path": path,
                           "message": "Exception token moved; regenerate and review exact position"})
        elif not has_han(token):
            errors.append({"code": "exception_stale_error", "entry": index, "path": path,
                           "message": "Exception no longer refers to a Han-bearing literal"})
        elif key in exempted:
            errors.append({"code": "exception_duplicate_error", "entry": index, "path": path,
                           "message": "Multiple exceptions refer to the same token"})
        else:
            exempted.add(key)
    violations = []
    token_count = han_count = 0
    for path, (_, tokens) in scanned.items():
        token_count += len(tokens)
        for token in tokens:
            if has_han(token):
                han_count += 1
                if (path, token.start, token.end) not in exempted:
                    violations.append({"code": "han_literal", "path": path, "kind": token.kind,
                                       "source": token.source, "start": token.start, "end": token.end,
                                       "line": token.line, "column": token.column})
    return GuardResult(files_scanned, token_count, han_count, len(exempted), tuple(violations), tuple(errors))


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--allowlist", type=Path, help="Default: scripts/display-text-exceptions.json under root")
    parser.add_argument("--json", action="store_true", help="Print report JSON to stdout; create no report file")
    args = parser.parse_args(argv)
    result = run_guard(args.root, args.allowlist)
    if args.json:
        print(json.dumps(result.to_dict(), ensure_ascii=True, indent=2))
    else:
        for issue in (*result.errors, *result.violations):
            where = issue.get("path", "allowlist")
            if "line" in issue:
                where += f":{issue['line']}:{issue['column']}"
            print(f"{where}: {issue['code']}: {issue.get('message', 'Han literal must move to AppText')}")
        print(f"display-text guard: {'PASS' if result.ok else 'FAIL'}; files={result.files_scanned}, "
              f"tokens={result.tokens_scanned}, Han={result.han_tokens}, exceptions={result.exempted_tokens}")
    return 0 if result.ok else 1


if __name__ == "__main__":
    sys.exit(main())
