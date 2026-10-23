#!/usr/bin/env python3
"""Fail-closed, stdlib-only Kotlin/Swift display-literal guard through Phase C.

Public generator API (Kotlin entry point remains unchanged):
    scan_source(source) -> list[Token]  # all tokens, including descendants
    scan_swift_source(source) -> list[Token]  # independent conservative lexer
    has_han(token) -> bool            # decoded literal parts only
    make_exception(path, source, token, reason, owner, *, category='protocol')

Scope: commonMain package ui/core/feature/data/platform/edition/**/*.kt, App.kt and
optional AppGraph.kt; shared/src/{androidMain,iosMain,jvmMain,standardMain,proMain}/kotlin/**/*.kt,
androidApp/src/main/kotlin/**/*.kt and iosApp/Sources/**/*.swift. Only the exact
commonMain core/text/AppText.kt is catalog-excluded. Every scope directory is
required; resources, tests and other source sets are not included.
Offsets are Python string offsets, end-exclusive; line/column are one-based.
Token.source includes delimiters. Literal parts have one segment per template
boundary (including empty segments); template expressions are never evaluated.
Children are immediate nested tokens, also present in the flat return value.
Read source with open(..., encoding='utf-8', newline='') to preserve CRLF.

Allowlist document: {"version": 1, "exceptions": [make_exception(...), ...]}.
Categories: protocol, lookup, historical deferred_program (TWO fingerprint-pinned
old program tokens only), and diagnostic_fixture (TWO exact synthetic Unicode
roundtrip use sites only, never wire values). No file/line exemptions. An entry
must uniquely match its exact path/kind/source, 48-character before/after context
AND all four position fields. Moved, stale, duplicate, ambiguous and non-Han
entries fail validation. Diagnostic counts are separate from protocol/lookup.

These are lexical scanners, not type checkers or full expression parsers.
Unsupported forms raise LexicalError; no source, callbacks or expressions run.
Swift supports ordinary/hash-delimited strings, scalar \\u{...} escapes, nested
\\(expression) strings/interpolations and unindented multiline literals. Indented
multiline closers, line continuations, regex/slash operators and non-string hash
syntax are deliberately unsupported and fail closed, not guessed as Kotlin.
"""
from __future__ import annotations

import argparse
from bisect import bisect_right
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path, PurePosixPath
import sys
import unicodedata
from typing import Sequence


CONTEXT_CHARS = 48
KOTLIN_BASE = "shared/src/commonMain/kotlin/io/github/betterzslf/"
UI_PREFIX = KOTLIN_BASE + "ui/"
APP_PATH = KOTLIN_BASE + "App.kt"
APP_GRAPH_PATH = KOTLIN_BASE + "AppGraph.kt"
CATALOG_PATH = KOTLIN_BASE + "core/text/AppText.kt"
SCOPE_PREFIXES = tuple(KOTLIN_BASE + name + "/" for name in ("ui", "core", "feature", "data", "platform", "edition"))
C_KOTLIN_PREFIXES = tuple("shared/src/" + name + "/kotlin/" for name in ("androidMain", "iosMain", "jvmMain", "standardMain", "proMain")) + (
    "androidApp/src/main/kotlin/",
)
SWIFT_PREFIX = "iosApp/Sources/"
TOKEN_KINDS = frozenset(("string", "raw_string", "char"))
EXCEPTION_CATEGORIES = ("protocol", "lookup", "deferred_program", "diagnostic_fixture")

# Exact diagnostic DATA, not display or wire text. Pin the original complete
# source SHA plus both 48-character contexts; usual checks still bind positions.
# The source pin is an extra condition for TWO tokens, never a file exemption.
_DIAGNOSTIC_PATH = "shared/src/iosMain/kotlin/io/github/betterzslf/platform/IosStartupDiagnostics.kt"
_DIAGNOSTIC_SOURCE = '"synthetic-中文"'
_DIAGNOSTIC_SOURCE_SHA256 = "ae2e29ad215c2637f8b7f936fbb5292d496fc5da60a8edc8664be38b94d3217e"
_DIAGNOSTIC_CONTEXTS = (
    ('mpty") == "")\n                first.put("utf8", ',
     ')\n                verify("UNICODE_VALUE", first.'),
    ('   verify("UNICODE_VALUE", first.get("utf8") == ',
     ')\n                first.remove("item")\n         '),
)

# Phase C debt, NOT protocol values. Pin token spelling (including delimiters),
# kind, declaration context and HTML/JS structure independently of the allowlist.
# Position and the full 48-character context still match the current source via
# the ordinary exact-entry checks; B-only edits require reviewed regeneration.
_DEFERRED_PROGRAMS = {
    KOTLIN_BASE + "platform/RunMapWeb.kt": (
        "raw_string", "40441487499900bac6eca619e3ba22369912c0948954682d996b1b1f7a4e6057",
        "        val html = ", "\n        return RunMapDocument(html, null)\n",
        ("<!doctype html>", "<html", "<script", "(function(){'use strict';",
         'aria-label="路线地图"', "document.write(", "</script></body></html>"),
    ),
    KOTLIN_BASE + "platform/RunMapBootstrapPolicy.kt": (
        "string", "540832e14e9a9199553d34978b66b6ec732a89235e733fb8c5c993bf107ec3dc",
        "    const val emptyDocument = ", "\n    private val nonce = Regex(",
        ("<!doctype html>", '<meta http-equiv="Content-Security-Policy"',
         "script-src 'none'", "<title>地图暂不可用</title>"),
    ),
}


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


class _SwiftScanner:
    """Small independent Swift lexer; ambiguous/unsupported forms are errors.

    Hash-delimited literals use raw_string; ordinary (including multiline) ones
    use string. Multiline support is intentionally limited to column-one closing
    delimiters so decoded parts never guess Swift's indentation stripping rules.
    """
    def __init__(self, source: str):
        self.source = source
        self.size = len(source)
        self.lines = _line_starts(source)

    def fail(self, message: str, offset: int):
        raise LexicalError(message, self.source, offset)

    def line_comment(self, start: int) -> int:
        i = start + 2
        while i < self.size and self.source[i] not in "\r\n":
            i += 1
        return i

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
        self.fail("Unterminated Swift block comment", start)

    def code(self, start: int = 0, *, interpolation: bool = False, depth: int = 0):
        source, tokens, stack = self.source, [], []
        pairs = {")": "(", "]": "[", "}": "{"}
        i, saw_code = start, False
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
                self.fail("Unexpected Swift block-comment terminator", i)
            if interpolation and char == ")" and not stack:
                if not saw_code:
                    self.fail("Empty Swift interpolation expression", start)
                return tokens, i + 1
            saw_code = True
            if char in ('"', '#'):
                token = self.literal(i, depth)
                tokens.append(token)
                i = token.end
                continue
            if char == "`":
                end = source.find("`", i + 1)
                name = source[i + 1:end] if end >= 0 else ""
                if (not name or not _identifier_start(name[0])
                        or any(not _identifier_part(c) for c in name[1:])):
                    self.fail("Unsupported Swift quoted identifier", i)
                i = end + 1
                continue
            if char == "$":
                i += 1
                if i >= self.size or not (_identifier_start(source[i]) or source[i] in "0123456789"):
                    self.fail("Unsupported Swift dollar syntax", i - 1)
                while i < self.size and _identifier_part(source[i]):
                    i += 1
                continue
            if _identifier_start(char):
                i += 1
                while i < self.size and _identifier_part(source[i]):
                    i += 1
                continue
            if char in "0123456789":
                i += 1
                continue
            if char in "({[":
                stack.append((char, i))
            elif char in ")}]":
                if not stack or stack[-1][0] != pairs[char]:
                    self.fail("Mismatched Swift code delimiter", i)
                stack.pop()
            elif char == "/":
                self.fail("Unsupported Swift slash operator or regex literal", i)
            elif char == "\\":
                if source[i + 1:i + 2] != ".":
                    self.fail("Unsupported Swift backslash syntax", i)
            elif char not in "+-*%=!<>?:;.,@&|~^":
                self.fail(f"Unsupported Swift character U+{ord(char):04X}", i)
            i += 1
        if stack:
            self.fail("Unterminated Swift code delimiter", stack[-1][1])
        if interpolation:
            self.fail("Unterminated Swift interpolation expression", start)
        return tokens, i

    def escape(self, start: int, value_start: int) -> tuple[str, int]:
        if value_start >= self.size:
            self.fail("Unterminated Swift escape", start)
        char = self.source[value_start]
        escapes = {"0": "\0", "t": "\t", "n": "\n", "r": "\r", "'": "'", '"': '"', "\\": "\\"}
        if char in escapes:
            return escapes[char], value_start + 1
        if self.source.startswith("u{", value_start):
            end = self.source.find("}", value_start + 2)
            digits = self.source[value_start + 2:end] if end >= 0 else ""
            if not (1 <= len(digits) <= 8) or any(c not in "0123456789abcdefABCDEF" for c in digits):
                self.fail("Invalid Swift Unicode escape (expected 1-8 hex digits in braces)", start)
            value = int(digits, 16)
            if value > 0x10FFFF or 0xD800 <= value <= 0xDFFF:
                self.fail("Swift Unicode escape must be a Unicode scalar", start)
            return chr(value), end + 1
        if char in "\r\n \t":
            self.fail("Unsupported Swift multiline continuation", start)
        self.fail("Unsupported Swift escape", start)

    def literal(self, start: int, depth: int) -> Token:
        if depth >= 100:
            self.fail("Supported Swift interpolation nesting depth exceeded", start)
        source, quote = self.source, start
        while quote < self.size and source[quote] == "#":
            quote += 1
        hashes = source[start:quote]
        if quote >= self.size or source[quote] != '"':
            self.fail("Unsupported Swift hash syntax (expected a raw string literal)", start)
        multiline = source.startswith('"""', quote)
        delimiter = ('"""' if multiline else '"') + hashes
        escape_prefix = "\\" + hashes
        i = quote + (3 if multiline else 1)
        if multiline:
            if source.startswith("\r\n", i):
                i += 2
            elif source[i:i + 1] == "\n":
                i += 1
            else:
                self.fail("Swift multiline literal requires an opening newline", i)
        body_start = i
        parts, chunk, holes, children = [], [], [], []
        while i < self.size:
            if source.startswith(delimiter, i):
                if multiline:
                    line_start = max(source.rfind("\n", 0, i), source.rfind("\r", 0, i)) + 1
                    if i != line_start:
                        self.fail("Unsupported Swift indented or inline multiline closing delimiter", i)
                    # The newline immediately before the closing delimiter is not value text.
                    if i != body_start:
                        if not chunk or chunk[-1] != "\n":
                            self.fail("Unsupported Swift multiline closing boundary", i)
                        chunk.pop()
                end = i + len(delimiter)
                # Extra delimiter characters are ambiguous here, never swallowed.
                if source[end:end + 1] in ('"', '#'):
                    self.fail("Unsupported Swift closing delimiter run", end)
                break
            char = source[i]
            if char in "\r\n":
                if not multiline:
                    self.fail("Newline in Swift single-line literal", i)
                chunk.append("\n")
                i += 2 if source.startswith("\r\n", i) else 1
                continue
            if source.startswith(escape_prefix, i):
                value_start = i + len(escape_prefix)
                if source[value_start:value_start + 1] == "(":
                    parts.append("".join(chunk))
                    chunk = []
                    expression_start = value_start + 1
                    nested, hole_end = self.code(expression_start, interpolation=True, depth=depth + 1)
                    holes.append(TemplateHole("expression", i, hole_end, expression_start, hole_end - 1,
                                              source[expression_start:hole_end - 1], tuple(nested)))
                    children.extend(nested)
                    i = hole_end
                    continue
                decoded, i = self.escape(i, value_start)
                chunk.append(decoded)
                continue
            if ord(char) < 32 and char != "\t":
                self.fail("Unsupported control character in Swift literal", i)
            chunk.append(char)
            i += 1
        else:
            self.fail("Unterminated Swift literal", start)
        parts.append("".join(chunk))
        line, column = _location(self.lines, start)
        return Token("raw_string" if hashes else "string", start, end, line, column,
                     source[start:end], tuple(parts), tuple(holes), tuple(children))


def scan_source(source: str) -> list[Token]:
    """Return every Kotlin string/char token, parents before children, in start order."""
    if not isinstance(source, str):
        raise TypeError("source must be str")
    roots, _ = _Scanner(source).code()
    return _flatten_tokens(roots)


def scan_swift_source(source: str) -> list[Token]:
    """Return Swift literal tokens in the same Token API; unsupported forms raise."""
    if not isinstance(source, str):
        raise TypeError("source must be str")
    roots, _ = _SwiftScanner(source).code()
    return _flatten_tokens(roots)


def _flatten_tokens(roots) -> list[Token]:
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
    if value == CATALOG_PATH or not (
        value in (APP_PATH, APP_GRAPH_PATH)
        or (value.endswith(".kt") and any(value.startswith(prefix) for prefix in (*SCOPE_PREFIXES, *C_KOTLIN_PREFIXES)))
        or (value.endswith(".swift") and value.startswith(SWIFT_PREFIX))
    ):
        raise ValueError("Exception path is outside Phase C Kotlin/Swift scope")
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
    if category == "diagnostic_fixture" and hashlib.sha256(source.encode("utf-8")).hexdigest() != _DIAGNOSTIC_SOURCE_SHA256:
        raise ValueError("diagnostic_fixture requires the original complete Native diagnostic source SHA")
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
    if entry["category"] not in EXCEPTION_CATEGORIES:
        raise ValueError("Exception category must be protocol, lookup, deferred_program or diagnostic_fixture")
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
    if entry["category"] == "deferred_program":
        _validate_deferred_program(entry)
    elif entry["category"] == "diagnostic_fixture":
        _validate_diagnostic_fixture(entry)
    elif entry["path"] in _DEFERRED_PROGRAMS and "<!doctype html>" in entry["source"]:
        raise ValueError("Embedded HTML programs require the explicit deferred_program category")
    elif entry["path"] == _DIAGNOSTIC_PATH and entry["source"] == _DIAGNOSTIC_SOURCE:
        raise ValueError("Synthetic Unicode roundtrip data requires diagnostic_fixture, never protocol/lookup")


def _validate_diagnostic_fixture(entry: dict) -> None:
    if entry["path"] != _DIAGNOSTIC_PATH:
        raise ValueError("diagnostic_fixture is restricted to IosStartupDiagnostics.kt's original two use sites")
    if entry["kind"] != "string" or entry["source"] != _DIAGNOSTIC_SOURCE:
        raise ValueError("diagnostic_fixture must retain the exact synthetic Unicode string token")
    context = (entry["context"]["before"], entry["context"]["after"])
    if context not in _DIAGNOSTIC_CONTEXTS:
        raise ValueError("diagnostic_fixture must retain the exact put/get Unicode roundtrip declaration context")


def _validate_deferred_program(entry: dict) -> None:
    specification = _DEFERRED_PROGRAMS.get(entry["path"])
    if specification is None:
        raise ValueError("deferred_program is restricted to the two known platform program paths")
    kind, fingerprint, before, after, markers = specification
    if entry["kind"] != kind:
        raise ValueError("deferred_program token kind differs from the known program")
    if hashlib.sha256(entry["source"].encode("utf-8")).hexdigest() != fingerprint:
        raise ValueError("deferred_program source differs from the exact known program token")
    if not entry["context"]["before"].endswith(before) or not entry["context"]["after"].startswith(after):
        raise ValueError("deferred_program must retain its exact program declaration context")
    tokens = scan_source(entry["source"])
    if len(tokens) != 1 or tokens[0].source != entry["source"] or tokens[0].kind != kind:
        raise ValueError("deferred_program must be one exact literal token without nested literals")
    if not has_han(tokens[0]) or any(marker not in tokens[0].decoded_literal for marker in markers):
        raise ValueError("deferred_program requires the known embedded HTML/program structure")


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
    # Trailing defaults preserve the six-argument positional constructor/API.
    protocol_tokens: int = 0
    lookup_tokens: int = 0
    deferred_tokens: int = 0
    diagnostic_tokens: int = 0

    @property
    def ok(self) -> bool:
        return not self.violations and not self.errors

    def to_dict(self) -> dict:
        return {"ok": self.ok, "files_scanned": self.files_scanned,
                "tokens_scanned": self.tokens_scanned, "han_tokens": self.han_tokens,
                "exempted_tokens": self.exempted_tokens,
                "protocol_tokens": self.protocol_tokens, "lookup_tokens": self.lookup_tokens,
                "deferred_tokens": self.deferred_tokens, "diagnostic_tokens": self.diagnostic_tokens,
                "violations": list(self.violations), "errors": list(self.errors)}


def run_guard(root, allowlist=None) -> GuardResult:
    """Scan required common/platform Kotlin and Swift roots; exclude only AppText.kt."""
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
    targets = []
    scopes = tuple((prefix, "*.kt") for prefix in (*SCOPE_PREFIXES, *C_KOTLIN_PREFIXES)) + ((SWIFT_PREFIX, "*.swift"),)
    for prefix, pattern in scopes:
        directory = root / prefix
        if not directory.is_dir():
            errors.append({"code": "scope_error", "path": prefix, "message": "Missing required display-text source directory"})
        else:
            targets.extend(sorted(directory.rglob(pattern)))
    app = root / APP_PATH
    if not app.is_file():
        errors.append({"code": "scope_error", "path": APP_PATH, "message": "Missing Phase B App.kt"})
    else:
        targets.append(app)
    app_graph = root / APP_GRAPH_PATH
    if app_graph.is_file():
        targets.append(app_graph)
    elif app_graph.exists():
        errors.append({"code": "scope_error", "path": APP_GRAPH_PATH, "message": "AppGraph.kt is not a source file"})
    files_scanned = 0
    for path in targets:
        relative = path.relative_to(root).as_posix()
        if relative == CATALOG_PATH:
            continue
        try:
            if not path.resolve().is_relative_to(root):
                raise ValueError("Scoped source resolves outside repository")
            with path.open("r", encoding="utf-8", newline="") as stream:
                source = stream.read()
            tokens = scan_swift_source(source) if relative.endswith(".swift") else scan_source(source)
            scanned[relative] = (source, tokens)
            files_scanned += 1
        except LexicalError as exc:
            errors.append({"code": "lexical_error", "path": relative, "message": exc.message,
                           "offset": exc.offset, "line": exc.line, "column": exc.column})
        except (OSError, ValueError) as exc:
            errors.append({"code": "source_error", "path": relative, "message": str(exc)})
    exempted = set()
    category_counts = dict.fromkeys(EXCEPTION_CATEGORIES, 0)
    for index, entry in enumerate(entries):
        path = entry["path"]
        source, tokens = scanned.get(path, ("", []))
        if entry["category"] == "deferred_program":
            candidates = [token for token in tokens
                          if token.kind == entry["kind"] and token.source == entry["source"]]
            if len(candidates) != 1:
                errors.append({"code": "exception_match_error", "entry": index, "path": path,
                               "message": f"Expected one known deferred program token, found {len(candidates)}"})
                continue
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
        elif entry["category"] == "diagnostic_fixture" and hashlib.sha256(source.encode("utf-8")).hexdigest() != _DIAGNOSTIC_SOURCE_SHA256:
            errors.append({"code": "diagnostic_source_error", "entry": index, "path": path,
                           "message": "Native diagnostic source changed; review the full-source SHA and exact fixture pair"})
        else:
            exempted.add(key)
            category_counts[entry["category"]] += 1
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
    return GuardResult(files_scanned, token_count, han_count, len(exempted), tuple(violations), tuple(errors),
                       protocol_tokens=category_counts["protocol"], lookup_tokens=category_counts["lookup"],
                       deferred_tokens=category_counts["deferred_program"],
                       diagnostic_tokens=category_counts["diagnostic_fixture"])


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
              f"tokens={result.tokens_scanned}, Han={result.han_tokens}, exceptions={result.exempted_tokens}, "
              f"protocol={result.protocol_tokens}, lookup={result.lookup_tokens}, deferred={result.deferred_tokens}")
    return 0 if result.ok else 1


if __name__ == "__main__":
    sys.exit(main())
