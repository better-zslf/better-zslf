"""Independent stdlib-only lexer/allowlist fixtures; no Kotlin execution or CI claims.

Run with PYTHONDONTWRITEBYTECODE=1 and python -B -m unittest discover
-s scripts/tests -p test_display_text_guard.py -v. Temporary repositories are
removed after each test. Expected text/offsets/schema are not imported from the
guard's implementation constants.
"""
import contextlib
import copy
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import display_text_guard as guard


class KotlinLexerTests(unittest.TestCase):
    # These first five retain the legacy lexical scenarios explicitly.
    def test_legacy_nested_comments(self):
        source = '/* 外 /* "隐藏" */ \'中\' */\nval x = "shown" // "旁注"\n'
        tokens = guard.scan_source(source)
        self.assertEqual([t.source for t in tokens], ['"shown"'])
        self.assertEqual(tokens[0].literal_parts, ("shown",))
        self.assertEqual((tokens[0].line, tokens[0].column), (2, 9))
        self.assertFalse(guard.has_han(tokens[0]))

    def test_legacy_nested_if_strings(self):
        source = 'val t = "${if (ok) "开" else "关"}"'
        tokens = guard.scan_source(source)
        self.assertEqual([t.source for t in tokens], ['"${if (ok) "开" else "关"}"', '"开"', '"关"'])
        self.assertEqual(tokens[0].literal_parts, ("", ""))
        self.assertEqual(tokens[0].holes[0].expression_source, 'if (ok) "开" else "关"')
        self.assertEqual([guard.has_han(t) for t in tokens], [False, True, True])

    def test_legacy_raw_interpolation(self):
        source = 'val s = """前${if (x) "内" else "后"}尾"""'
        tokens = guard.scan_source(source)
        self.assertEqual([t.kind for t in tokens], ["raw_string", "string", "string"])
        self.assertEqual([t.literal_parts for t in tokens], [("前", "尾"), ("内",), ("后",)])
        self.assertEqual(tokens[0].holes[0].expression_source, 'if (x) "内" else "后"')

    def test_legacy_escaped_dollar(self):
        tokens = guard.scan_source(r'"\$label \$中文"')
        self.assertEqual(len(tokens), 1)
        self.assertEqual(tokens[0].literal_parts, ("$label $中文",))
        self.assertEqual(tokens[0].holes, ())
        self.assertEqual(tokens[0].children, ())
        self.assertTrue(guard.has_han(tokens[0]))

    def test_legacy_callback(self):
        source = 'val callback = { Text("确认"); next("ASCII") }'
        tokens = guard.scan_source(source)
        self.assertEqual([t.source for t in tokens], ['"确认"', '"ASCII"'])
        self.assertEqual([guard.has_han(t) for t in tokens], [True, False])

    def test_absolute_offsets_holes_and_containment(self):
        tokens = guard.scan_source('"a${"中"}b"')
        outer, child = tokens
        self.assertEqual((outer.start, outer.end, outer.line, outer.column), (0, 10, 1, 1))
        self.assertEqual((child.start, child.end, child.line, child.column), (4, 7, 1, 5))
        self.assertEqual(outer.literal_parts, ("a", "b"))
        hole = outer.holes[0]
        self.assertEqual((hole.kind, hole.start, hole.end, hole.expression_start, hole.expression_end),
                         ("expression", 2, 8, 4, 7))
        self.assertEqual(hole.expression_source, '"中"')
        self.assertIs(outer.children[0], child)
        self.assertIs(hole.children[0], child)
        self.assertEqual(outer.raw_source, '"a${"中"}b"')
        self.assertEqual(outer.decoded_literal, "ab")

    def test_three_level_template_tree(self):
        tokens = guard.scan_source('"a${"b${"中"}c"}d"')
        self.assertEqual([t.source for t in tokens], ['"a${"b${"中"}c"}d"', '"b${"中"}c"', '"中"'])
        self.assertEqual([t.literal_parts for t in tokens], [("a", "d"), ("b", "c"), ("中",)])
        self.assertEqual([guard.has_han(t) for t in tokens], [False, False, True])
        self.assertEqual(tokens[0].children, (tokens[1],))
        self.assertEqual(tokens[1].children, (tokens[2],))

    def test_nested_expression_comments_brackets_chars_and_line_comment(self):
        source = '''"""${call(/* } "假" /* nested */ */ mapOf("键" to '{'), { a -> a["值"] }) // }
}"""'''
        tokens = guard.scan_source(source)
        self.assertEqual([t.source for t in tokens[1:]], ['"键"', "'{'", '"值"'])
        self.assertEqual(tokens[0].holes[0].expression_source,
                         '''call(/* } "假" /* nested */ */ mapOf("键" to '{'), { a -> a["值"] }) // }
''')
        self.assertEqual(tokens[0].literal_parts, ("", ""))
        self.assertFalse(guard.has_han(tokens[0]))

    def test_identifier_holes_with_chinese_identifiers_are_not_literal_han(self):
        token = guard.scan_source('"$用户 ${中文变量} OK"')[0]
        self.assertEqual(token.literal_parts, ("", " ", " OK"))
        self.assertEqual([h.kind for h in token.holes], ["identifier", "expression"])
        self.assertEqual([h.expression_source for h in token.holes], ["用户", "中文变量"])
        self.assertEqual((token.holes[0].start, token.holes[0].end), (1, 4))
        self.assertEqual((token.holes[0].expression_start, token.holes[0].expression_end), (2, 4))
        self.assertFalse(guard.has_han(token))

    def test_empty_literal_parts_preserved_around_adjacent_holes(self):
        token = guard.scan_source('"$a${b}$c"')[0]
        self.assertEqual(token.literal_parts, ("", "", "", ""))
        self.assertEqual([h.expression_source for h in token.holes], ["a", "b", "c"])

    def test_escaped_quotes_backslash_dollar_and_unicode(self):
        token = guard.scan_source(r'"\$name \" \\ \u4E2D"')[0]
        self.assertEqual(token.literal_parts, ('$name " \\ 中',))
        self.assertEqual(token.holes, ())
        self.assertTrue(guard.has_han(token))

    def test_unicode_escaped_dollar_never_begins_a_template(self):
        token = guard.scan_source(r'"\u0024{literal}"')[0]
        self.assertEqual(token.literal_parts, ("${literal}",))
        self.assertEqual(token.holes, ())

    def test_raw_backslashes_are_not_escapes(self):
        token = guard.scan_source(r'"""\u4E2D\n"""')[0]
        self.assertEqual(token.literal_parts, (r"\u4E2D\n",))
        self.assertFalse(guard.has_han(token))

    def test_raw_backslash_does_not_escape_template_dollar(self):
        token = guard.scan_source(r'"""\$name"""')[0]
        self.assertEqual(token.literal_parts, ("\\", ""))
        self.assertEqual(token.holes[0].expression_source, "name")

    def test_raw_newlines_and_quote_runs(self):
        token = guard.scan_source('"""a\n"b""c\n"""')[0]
        self.assertEqual(token.literal_parts, ('a\n"b""c\n',))
        quoted = guard.scan_source('""""x""""')[0]
        self.assertEqual(quoted.literal_parts, ('"x"',))
        self.assertEqual(quoted.end, 9)

    def test_chinese_and_escaped_char_literals(self):
        source = r'''val a = '中'; val b = '\u6587'; val c='\''; val d='\\'; val e='$' '''
        tokens = guard.scan_source(source)
        self.assertEqual([t.kind for t in tokens], ["char"] * 5)
        self.assertEqual([t.literal_parts for t in tokens], [("中",), ("文",), ("'",), ("\\",), ("$",)])
        self.assertEqual([guard.has_han(t) for t in tokens], [True, True, False, False, False])

    def test_utf16_surrogate_escapes_decode_supplementary_han(self):
        token = guard.scan_source(r'"\uD840\uDC00"')[0]
        self.assertEqual(token.literal_parts, ("\U00020000",))
        self.assertTrue(guard.has_han(token))
        single = guard.scan_source(r"'\uD840'")[0]
        self.assertEqual(single.literal_parts, ("\ud840",))
        self.assertFalse(guard.has_han(single))

    def test_extended_han_blocks_and_non_han_literals(self):
        for text in ("\u3400", "\uf900", "\U00020000", "\U0002f800", "\U00030000", "\U000323b0", "〇"):
            with self.subTest(codepoint=ord(text)):
                self.assertTrue(guard.has_han(guard.scan_source('"' + text + '"')[0]))
        for text in ("ASCII", "かな", "한글", "😀", "123", ""):
            with self.subTest(text=text):
                self.assertFalse(guard.has_han(guard.scan_source('"' + text + '"')[0]))

    def test_crlf_and_lone_cr_line_column(self):
        tokens = guard.scan_source('"a"\r\n\'中\'\r"x"')
        self.assertEqual([(t.start, t.end, t.line, t.column) for t in tokens],
                         [(0, 3, 1, 1), (5, 8, 2, 1), (9, 12, 3, 1)])

    def test_quoted_identifiers_comments_and_shebang_do_not_make_tokens(self):
        source = '#!/usr/bin/env kotlin "隐藏"\nval `中文"identifier` = "ok" /* \'中\' */'
        self.assertEqual([t.source for t in guard.scan_source(source)], ['"ok"'])

    def test_literal_dollars_before_non_identifiers(self):
        token = guard.scan_source('"$5 $! $$"')[0]
        self.assertEqual(token.literal_parts, ("$5 $! $$",))
        self.assertEqual(token.holes, ())

    def test_unknown_and_malformed_syntax_fail_explicitly(self):
        sources = [r'"\x41"', r'"\q"', r'"\u12"', r'"\uZZZZ"', '"bad\nline"',
                   "''", "'ab'", "'😀'", '"unclosed', '"""unclosed', '/* open',
                   'val x = $$"""中文"""', 'val x = $"plain"', 'val x = \\x',
                   'val x = 😀', '"$`name`"', '"${}"', '"${/* only */}"',
                   '"${call(]}"', '"${value"', 'val x = (`open', 'val x = [1)', '*/']
        for source in sources:
            with self.subTest(source=source):
                with self.assertRaises(guard.LexicalError):
                    guard.scan_source(source)

    def test_error_location_is_explicit(self):
        with self.assertRaises(guard.LexicalError) as raised:
            guard.scan_source('val a=1\r\nval b="bad\n"')
        error = raised.exception
        self.assertEqual((error.offset, error.line, error.column), (19, 2, 11))
        self.assertIn("Newline", error.message)

    def test_excessive_template_nesting_fails_with_lexical_error(self):
        source = '"ok"'
        for _ in range(105):
            source = '"${' + source + '}"'
        with self.assertRaises(guard.LexicalError):
            guard.scan_source(source)

    def test_source_type_is_not_implicitly_converted(self):
        with self.assertRaises(TypeError):
            guard.scan_source(b'"ASCII"')


class GuardExceptionTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="display-text-guard-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.ui_path = "shared/src/commonMain/kotlin/io/github/betterzslf/ui/Sample.kt"
        self.app_path = "shared/src/commonMain/kotlin/io/github/betterzslf/App.kt"
        self.allowlist = self.root / "scripts/display-text-exceptions.json"
        self.write(self.app_path, "fun App() = Unit\n")
        self.source = 'val protocol = "协议"\n'
        self.write(self.ui_path, self.source)
        self.allow([])

    def write(self, relative, source):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("w", encoding="utf-8", newline="") as stream:
            stream.write(source)

    def allow(self, entries):
        self.allowlist.parent.mkdir(parents=True, exist_ok=True)
        self.allowlist.write_text(json.dumps({"version": 1, "exceptions": entries}, ensure_ascii=True), encoding="utf-8")

    def entry(self, source=None, index=0, **kwargs):
        source = self.source if source is None else source
        return guard.make_exception(self.ui_path, source, guard.scan_source(source)[index],
                                    "Exact upstream protocol value", "campus-api", **kwargs)

    def run_guard(self):
        return guard.run_guard(self.root)

    def test_empty_allowlist_catches_han_and_scans_non_han_tokens(self):
        self.write(self.app_path, 'fun App() = Text("ASCII")\n')
        result = self.run_guard()
        self.assertFalse(result.ok)
        self.assertEqual((result.files_scanned, result.tokens_scanned, result.han_tokens, result.exempted_tokens),
                         (2, 2, 1, 0))
        self.assertEqual(result.errors, ())
        self.assertEqual(result.violations, ({"code": "han_literal", "path": self.ui_path,
                                            "kind": "string", "source": '"协议"',
                                            "start": 15, "end": 19, "line": 1, "column": 16},))

    def test_factory_exact_schema_and_values(self):
        self.assertEqual(self.entry(), {
            "path": "shared/src/commonMain/kotlin/io/github/betterzslf/ui/Sample.kt",
            "kind": "string", "source": '"协议"',
            "context": {"before": "val protocol = ", "after": "\n"},
            "position": {"start": 15, "end": 19, "line": 1, "column": 16},
            "category": "protocol", "reason": "Exact upstream protocol value", "owner": "campus-api"})

    def test_exact_protocol_exception_passes(self):
        self.allow([self.entry()])
        result = self.run_guard()
        self.assertTrue(result.ok, result.to_dict())
        self.assertEqual((result.han_tokens, result.exempted_tokens), (1, 1))

    def test_lookup_char_exception_is_supported(self):
        source = "val marker = '中'\n"
        self.write(self.ui_path, source)
        self.allow([self.entry(source, category="lookup")])
        self.assertTrue(self.run_guard().ok)

    def test_protocol_and_label_on_same_line_are_not_blanket_exempted(self):
        source = 'val key = "协议"; Text("按钮")\n'
        self.write(self.ui_path, source)
        self.allow([self.entry(source)])
        result = self.run_guard()
        self.assertEqual(result.exempted_tokens, 1)
        self.assertEqual([v["source"] for v in result.violations], ['"按钮"'])
        self.assertFalse(result.ok)

    def test_nested_expression_han_is_a_separate_violation(self):
        source = 'Text("${if (ok) "中文" else "ASCII"}")\n'
        self.write(self.ui_path, source)
        result = self.run_guard()
        self.assertEqual(result.tokens_scanned, 3)
        self.assertEqual([v["source"] for v in result.violations], ['"中文"'])

    def test_unicode_escaped_han_fails_guard(self):
        self.write(self.ui_path, r'val x = "\u4E2D"')
        result = self.run_guard()
        self.assertEqual(result.han_tokens, 1)
        self.assertEqual(result.violations[0]["source"], r'"\u4E2D"')

    def test_duplicate_token_values_require_their_own_context(self):
        source = 'val first = "状态"\nval second = "状态"\n'
        self.write(self.ui_path, source)
        first, second = self.entry(source, 0), self.entry(source, 1)
        self.assertNotEqual(first["context"], second["context"])
        self.allow([first])
        result = self.run_guard()
        self.assertEqual(len(result.violations), 1)
        self.assertEqual(result.violations[0]["line"], 2)
        self.allow([first, second])
        self.assertTrue(self.run_guard().ok)

    def test_ambiguous_repeated_context_fails_even_with_exact_position(self):
        segment = "a" * 60 + ' val key = "协议";' + "b" * 60 + ";\n"
        source = segment + segment
        self.write(self.ui_path, source)
        self.allow([self.entry(source)])
        result = self.run_guard()
        self.assertFalse(result.ok)
        self.assertEqual(result.errors[0]["code"], "exception_match_error")
        self.assertIn("found 2", result.errors[0]["message"])
        self.assertEqual(result.exempted_tokens, 0)

    def test_duplicate_entries_fail(self):
        entry = self.entry()
        self.allow([entry, copy.deepcopy(entry)])
        result = self.run_guard()
        self.assertFalse(result.ok)
        self.assertEqual(result.errors[0]["code"], "exception_duplicate_error")

    def test_stale_token_change_fails(self):
        self.allow([self.entry()])
        self.write(self.ui_path, 'val protocol = "新协议"\n')
        result = self.run_guard()
        self.assertEqual(result.errors[0]["code"], "exception_match_error")
        self.assertEqual(result.exempted_tokens, 0)

    def test_stale_context_change_fails(self):
        self.allow([self.entry()])
        self.write(self.ui_path, 'val protocol = "协议"; Text("ASCII")\n')
        result = self.run_guard()
        self.assertEqual(result.errors[0]["code"], "exception_match_error")

    def test_deleted_token_or_file_leaves_stale_exception(self):
        self.allow([self.entry()])
        self.write(self.ui_path, "val protocol = upstreamValue\n")
        result = self.run_guard()
        self.assertEqual(result.violations, ())
        self.assertEqual(result.errors[0]["code"], "exception_match_error")
        (self.root / self.ui_path).unlink()
        self.assertFalse(self.run_guard().ok)

    def test_line_shift_outside_context_fails_position_check(self):
        source = "// padding\n" * 8 + self.source
        self.write(self.ui_path, source)
        self.allow([self.entry(source)])
        self.write(self.ui_path, "\n" + source)
        result = self.run_guard()
        self.assertEqual(result.errors[0]["code"], "exception_position_error")
        self.assertEqual(result.exempted_tokens, 0)

    def test_line_change_at_same_offset_still_fails_position_check(self):
        source = "//" + "x" * 80 + "\n" + self.source
        self.write(self.ui_path, source)
        self.allow([self.entry(source)])
        self.write(self.ui_path, source[:2] + "\n" + source[3:])
        result = self.run_guard()
        self.assertEqual(result.errors[0]["code"], "exception_position_error")

    def test_fixed_context_is_48_characters_not_a_line_exemption(self):
        source = "//" + "x" * 80 + "\n" + self.source + "//" + "y" * 80 + "\n"
        entry = self.entry(source)
        self.assertEqual(len(entry["context"]["before"]), 48)
        self.assertEqual(len(entry["context"]["after"]), 48)
        self.write(self.ui_path, source[:2] + "z" + source[3:])
        self.allow([entry])
        self.assertTrue(self.run_guard().ok)

    def test_crlf_exact_source_and_context_are_preserved(self):
        source = 'val x = """甲\r\n乙"""\r\n'
        self.write(self.ui_path, source)
        entry = self.entry(source)
        self.assertEqual(entry["source"], '"""甲\r\n乙"""')
        self.assertEqual(entry["context"]["after"], "\r\n")
        self.allow([entry])
        self.assertTrue(self.run_guard().ok)
        self.write(self.ui_path, source.replace("\r\n", "\n"))
        self.assertFalse(self.run_guard().ok)

    def test_non_han_exception_is_stale_even_if_token_and_context_match(self):
        entry = self.entry()
        entry["source"] = '"ASCII"'
        entry["position"]["end"] = 22
        self.write(self.ui_path, 'val protocol = "ASCII"\n')
        self.allow([entry])
        result = self.run_guard()
        self.assertEqual(result.errors[0]["code"], "exception_stale_error")

    def test_missing_allowlist_fails_closed(self):
        self.allowlist.unlink()
        result = self.run_guard()
        self.assertFalse(result.ok)
        self.assertEqual(result.errors[0]["code"], "allowlist_error")

    def test_bad_entry_schema_metadata_and_positions_fail_closed(self):
        mutations = [{"owner": ""}, {"reason": " "}, {"category": "display"},
                     {"kind": "line"}, {"context": {}}, {"position": {}},
                     {"position": {"start": False, "end": 19, "line": 1, "column": 16}}]
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                entry = self.entry()
                entry.update(mutation)
                self.allow([entry])
                self.assertEqual(self.run_guard().errors[0]["code"], "allowlist_error")
        self.allow([{"path": self.ui_path, "reason": "blanket file bypass"}])
        self.assertEqual(self.run_guard().errors[0]["code"], "allowlist_error")

    def test_duplicate_json_keys_and_unknown_versions_fail(self):
        for text in ('{"version":1,"version":1,"exceptions":[]}',
                     '{"version":true,"exceptions":[]}', '{"version":2,"exceptions":[]}',
                     '{"version":1,"exceptions":[],"wholeFile":true}', '[]', '{'):
            with self.subTest(document=text):
                self.allowlist.write_text(text, encoding="utf-8")
                self.assertEqual(self.run_guard().errors[0]["code"], "allowlist_error")

    def test_factory_rejects_wrong_source_out_of_scope_and_non_han(self):
        token = guard.scan_source(self.source)[0]
        bad_paths = ["../Sample.kt", "/tmp/Sample.kt", "shared/src/commonMain/kotlin/io/github/betterzslf/core/text/AppText.kt",
                     "shared/src/commonMain/kotlin/io/github/betterzslf/ui/../App.kt", "C:/Sample.kt"]
        for path in bad_paths:
            with self.subTest(path=path), self.assertRaises(ValueError):
                guard.make_exception(path, self.source, token, "protocol", "owner")
        with self.assertRaises(ValueError):
            guard.make_exception(self.ui_path, " " + self.source, token, "protocol", "owner")
        with self.assertRaises(ValueError):
            guard.make_exception(self.ui_path, '"ASCII"', guard.scan_source('"ASCII"')[0], "protocol", "owner")

    def test_scope_excludes_core_feature_tests_deps_and_other_source_sets(self):
        self.write(self.ui_path, 'val label = "ASCII"\n')
        outside = ["shared/src/commonMain/kotlin/io/github/betterzslf/core/text/AppText.kt",
                   "shared/src/commonMain/kotlin/io/github/betterzslf/core/Other.kt",
                   "shared/src/commonMain/kotlin/io/github/betterzslf/feature/B.kt",
                   "shared/src/commonTest/kotlin/io/github/betterzslf/ui/Bad.kt",
                   "shared/src/androidMain/kotlin/io/github/betterzslf/ui/Bad.kt",
                   "tests/ui/Bad.kt", "deps/ui/Bad.kt"]
        for path in outside:
            self.write(path, '$$"""中文 unsupported ignored outside scope"""')
        result = self.run_guard()
        self.assertTrue(result.ok, result.to_dict())
        self.assertEqual(result.files_scanned, 2)
        self.write("shared/src/commonMain/kotlin/io/github/betterzslf/ui/nested/Child.kt", 'val x = "界面"')
        result = self.run_guard()
        self.assertEqual(result.files_scanned, 3)
        self.assertEqual([v["source"] for v in result.violations], ['"界面"'])

    def test_app_is_always_in_scope(self):
        self.write(self.ui_path, "val x = Unit")
        self.write(self.app_path, 'Text("应用")')
        result = self.run_guard()
        self.assertEqual([v["path"] for v in result.violations], [self.app_path])

    def test_missing_scope_is_not_a_vacuous_pass(self):
        (self.root / self.app_path).unlink()
        self.assertIn("scope_error", [e["code"] for e in self.run_guard().errors])
        self.assertFalse(guard.run_guard(self.root / "absent").ok)

    def test_unsupported_syntax_in_scope_fails_closed(self):
        self.write(self.ui_path, '$$"""中文"""')
        result = self.run_guard()
        self.assertEqual(result.errors[0]["code"], "lexical_error")
        self.assertEqual((result.errors[0]["line"], result.errors[0]["column"]), (1, 1))

    def test_exception_kind_is_exact(self):
        entry = self.entry()
        entry["kind"] = "raw_string"
        self.allow([entry])
        result = self.run_guard()
        self.assertEqual(result.errors[0]["code"], "exception_match_error")

    def test_cli_json_stdout_and_exit_status_without_report_files(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            status = guard.main(["--root", str(self.root), "--json"])
        report = json.loads(output.getvalue())
        self.assertEqual(status, 1)
        self.assertFalse(report["ok"])
        self.assertEqual(report["violations"][0]["source"], '"协议"')
        self.allow([self.entry()])
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(guard.main(["--root", str(self.root), "--allowlist", str(self.allowlist), "--json"]), 0)
        self.assertEqual(sorted(p.name for p in (self.root / "scripts").iterdir()), ["display-text-exceptions.json"])


class RepositoryDisplayTextGateTests(unittest.TestCase):
    def test_current_scope_and_android_ci_entry_are_guarded(self):
        root = Path(__file__).resolve().parents[2]
        result = guard.run_guard(root)
        self.assertTrue(result.ok, repr({"errors": result.errors, "violations": result.violations}))
        shell = (root / "scripts/ci-android.sh").read_text(encoding="utf-8")
        command = "python3 scripts/display_text_guard.py --root ."
        self.assertEqual(shell.count(command), 1)
        self.assertLess(shell.index(command), shell.index("bash ./gradlew"))
        workflow = (root / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        self.assertIn("run: bash scripts/ci-android.sh", workflow.split("  ios:", 1)[0])


if __name__ == "__main__":
    unittest.main()
