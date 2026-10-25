"""Independent stdlib-only lexer/allowlist fixtures; no Kotlin execution or CI claims.

Run with PYTHONDONTWRITEBYTECODE=1 and python -B -m unittest discover
-s scripts/tests -p test_display_text_guard.py -v. Temporary repositories are
removed after each test. Expected text/offsets/schema are not imported from the
guard's implementation constants.
"""
import contextlib
import copy
import hashlib
import io
import json
from pathlib import Path
import re
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import display_text_guard as guard


# Independent scope expectations; do not derive these roots from the guard.
PHASE_C_ROOTS = (
    ("shared/src/androidMain/kotlin/", "kt"),
    ("shared/src/iosMain/kotlin/", "kt"),
    ("shared/src/jvmMain/kotlin/", "kt"),
    ("androidApp/src/main/kotlin/", "kt"),
    ("iosApp/Sources/", "swift"),
)
DIAGNOSTIC_PATH = "shared/src/iosMain/kotlin/io/github/betterzslf/platform/IosStartupDiagnostics.kt"
# Full diagnostic text is fixture DATA only; this independent SHA freezes it.
# It is never imported/executed. Every exception still matches just one token.
DIAGNOSTIC_SOURCE_SHA256 = "ae2e29ad215c2637f8b7f936fbb5292d496fc5da60a8edc8664be38b94d3217e"
with (Path(__file__).resolve().parents[2] / DIAGNOSTIC_PATH).open(encoding="utf-8", newline="") as stream:
    DIAGNOSTIC_FIXTURE = stream.read()


# Independent, frozen token fixtures for the TWO Phase C program debts. They
# contain target text only: tests lex it but never execute HTML/JS or Kotlin.
# These do not read live repository programs or import the guard's fingerprints.
EMPTY_MAP_DOCUMENT = r'''"<!doctype html><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'none'\"><title>地图暂不可用</title>"'''
RUN_MAP_HTML = r'''"""<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<meta http-equiv="Content-Security-Policy" content="$csp">
<style>html,body,#map{width:100%;height:100%;margin:0;overflow:hidden}body{background:#eef2f5}.checkpoint-label{display:inline-block;background:#fff;color:#17212b;border:2px solid #34F18C;border-radius:8px;padding:4px 7px;font:13px sans-serif;white-space:pre-wrap;max-width:180px}.position-dot{width:12px;height:12px;background:#2688ff;border:2px solid white;border-radius:50%}</style>
</head><body><div id="map" role="img" aria-label="路线地图"></div>
<script id="run-map-data" type="application/json">$data</script>
<script nonce="$documentId">
(function(){'use strict';
  let state=JSON.parse(document.getElementById('run-map-data').textContent);
  const documentId=state.documentId, provider=state.provider, frame=state.frame;
  let map=null, overlays=[], ready=false, failed=false, timer=null, poll=null;
  function post(type,extra){
    const value=Object.assign({documentId:documentId,type:type},extra||{});
    const message=JSON.stringify(value);
    try{
      if(window.webkit&&window.webkit.messageHandlers&&window.webkit.messageHandlers.runMap){window.webkit.messageHandlers.runMap.postMessage(message);}
      else if(window.runMap&&typeof window.runMap.postMessage==='function'){window.runMap.postMessage(message);}
    }catch(_){/* No URLs, coordinates or keys in logs. */}
  }
  function fail(reason){if(failed){return;}failed=true;clearTimeout(timer);clearInterval(poll);post('failed',{reason:reason});}
  function denyCallback(_success,error){if(typeof error==='function'){setTimeout(function(){error({code:1,message:'Permission denied'});},0);}return -1;}
  function denyMedia(){return Promise.reject(new DOMException('Permission denied','NotAllowedError'));}
  function marked(value){try{Object.defineProperty(value,'__runMapDenied',{value:true});}catch(_){}return value;}
  const geo=Object.freeze(marked({getCurrentPosition:denyCallback,watchPosition:denyCallback,clearWatch:function(){}}));
  marked(denyCallback);marked(denyMedia);
  function lock(object,name,value){
    if(!object){return true;}
    try{
      const old=Object.getOwnPropertyDescriptor(object,name);
      if(old&&!old.configurable){return !!(old.value&&old.value.__runMapDenied);}
      Object.defineProperty(object,name,{value:value,writable:false,configurable:false});return true;
    }catch(_){return false;}
  }
  function permissions(){
    const proto=Object.getPrototypeOf(navigator);
    let ok=lock(proto,'geolocation',geo)&&lock(navigator,'geolocation',geo);
    ['getUserMedia','webkitGetUserMedia','mozGetUserMedia','msGetUserMedia'].forEach(function(name){ok=lock(proto,name,denyCallback)&&lock(navigator,name,denyCallback)&&ok;});
    const media=navigator.mediaDevices;
    if(media){const mp=Object.getPrototypeOf(media);ok=lock(mp,'getUserMedia',denyMedia)&&lock(media,'getUserMedia',denyMedia)&&ok;ok=lock(mp,'getDisplayMedia',denyMedia)&&lock(media,'getDisplayMedia',denyMedia)&&ok;}
    return ok;
  }
  function labelNode(text){const node=document.createElement('span');node.className='checkpoint-label';node.textContent=text;return node;}
  function point(value){return provider==='AMAP'?new AMap.LngLat(value[0],value[1]):new T.LngLat(value[0],value[1]);}
  function add(overlay){if(provider==='AMAP'){map.add(overlay);}else{map.addOverLay(overlay);}overlays.push(overlay);}
  function selected(id){post('checkpoint',{id:id});}
  function line(segment,color){
    if(segment.length<2){return;}
    const points=segment.map(point);
    add(provider==='AMAP'?new AMap.Polyline({path:points,strokeColor:color,strokeWeight:8,strokeOpacity:1}):new T.Polyline(points,{color:color,weight:8,opacity:1}));
  }
  function render(){
    if(!map){return;}
    overlays.forEach(function(overlay){try{if(provider==='AMAP'){map.remove(overlay);}else{map.removeOverLay(overlay);}}catch(_){}});overlays=[];
    state.recommended.forEach(function(segment){line(segment,'#34F18C');});
    state.actual.forEach(function(segment){line(segment,'#2688FF');});
    state.imported.forEach(function(segment){line(segment,'#F39B24');});
    state.checkpoints.forEach(function(checkpoint){
      const node=labelNode(checkpoint.label), position=point(checkpoint.point);
      if(provider==='AMAP'){
        const marker=new AMap.Marker({position:position,content:node});marker.on('click',function(){selected(checkpoint.id);});add(marker);
      }else{
        const marker=new T.Marker(position);marker.addEventListener('click',function(){selected(checkpoint.id);});add(marker);
        // T.Label takes markup; outerHTML comes ONLY from a fresh span filled via textContent.
        const label=new T.Label({position:position,text:node.outerHTML});label.addEventListener('click',function(){selected(checkpoint.id);});add(label);
      }
    });
    if(state.current){
      if(provider==='AMAP'){const node=document.createElement('span');node.className='position-dot';add(new AMap.Marker({position:point(state.current),content:node}));}
      else{add(new T.Marker(point(state.current)));}
    }
    document.body.style.background=state.dark?'#171b22':'#eef2f5';
  }
  function onReady(){if(ready||failed){return;}ready=true;clearTimeout(timer);clearInterval(poll);post('ready');}
  function clicked(event){
    if(!state.editMode||failed){return;}
    const value=event&&event.lnglat;if(!value){return;}
    const longitude=typeof value.getLng==='function'?value.getLng():value.lng;
    const latitude=typeof value.getLat==='function'?value.getLat():value.lat;
    if(Number.isFinite(longitude)&&Number.isFinite(latitude)&&longitude>=-180&&longitude<=180&&latitude>=-90&&latitude<=90){post('draw',{longitude:longitude,latitude:latitude,frame:frame});}
  }
  function initialize(){
    if(map||failed){return;}
    if(provider==='AMAP'&&(!window.AMap||!AMap.Map)){return;}
    if(provider==='TIAN_DI_TU'&&(!window.T||!T.Map)){return;}
    try{
      const center=point(state.center);
      if(provider==='AMAP'){
        map=new AMap.Map('map',{center:center,zoom:state.zoom,resizeEnable:true});map.on('complete',onReady);map.on('click',clicked);
      }else{
        const suffix='&SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&FORMAT=tiles&TILEMATRIXSET=w&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&tk='+encodeURIComponent(state.apiKey);
        const vector=new T.TileLayer('https://t0.tianditu.gov.cn/vec_w/wmts?LAYER=vec'+suffix,{minZoom:1,maxZoom:18});
        const labels=new T.TileLayer('https://t0.tianditu.gov.cn/cva_w/wmts?LAYER=cva'+suffix,{minZoom:1,maxZoom:18});
        map=new T.Map('map',{layers:[vector,labels]});map.addEventListener('load',onReady);map.addEventListener('click',clicked);map.centerAndZoom(center,state.zoom);
      }
      render();
    }catch(_){fail('SDK');}
  }
  function apply(next){
    if(!next||next.documentId!==documentId||next.provider!==provider||next.frame!==frame||next.apiKey!==state.apiKey||failed){return false;}
    state=next;
    try{render();return true;}catch(_){fail('SDK');return false;}
  }
  Object.defineProperty(window,'__runMapApply',{value:apply,writable:false,configurable:false});
  window.addEventListener('error',function(event){if(event&&typeof event.preventDefault==='function'){event.preventDefault();}fail(event&&event.target&&event.target.tagName==='SCRIPT'?'NETWORK':'SDK');},true);
  window.addEventListener('unhandledrejection',function(event){if(event&&typeof event.preventDefault==='function'){event.preventDefault();}fail('SDK');});
  window.addEventListener('securitypolicyviolation',function(){fail('SDK');});
  if(!permissions()){fail('SDK');return;}
  timer=setTimeout(function(){if(!ready){fail('NETWORK');}},15000);
  poll=setInterval(initialize,100);window.addEventListener('load',initialize);
  // Fixed provider tag only, AFTER immutable permission guards, while the parser is active.
  document.write($sdkTag);
})();
</script></body></html>"""'''


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


class SwiftLexerTests(unittest.TestCase):
    def test_comments_are_nested_and_never_emit_literals(self):
        source = '/* 外 /* "隐藏" */ */\nlet x = "ASCII" // "旁注"\n'
        tokens = guard.scan_swift_source(source)
        self.assertEqual([t.source for t in tokens], ['"ASCII"'])
        self.assertEqual((tokens[0].line, tokens[0].column), (2, 9))
        self.assertFalse(guard.has_han(tokens[0]))

    def test_ordinary_escapes_quotes_backslash_and_unicode(self):
        token = guard.scan_swift_source(r'''"\" \\ \' \0 \t \n \r \u{4E2D}"''')[0]
        self.assertEqual(token.kind, "string")
        self.assertEqual(token.literal_parts, ('" \\ \' \0 \t \n \r 中',))
        self.assertEqual(token.holes, ())
        self.assertTrue(guard.has_han(token))

    def test_unicode_scalars_include_supplementary_han(self):
        for escaped, text in ((r'\u{4e2d}', "中"), (r'\u{00006587}', "文"),
                              (r'\u{20000}', "\U00020000"), (r'\u{323B0}', "\U000323b0")):
            with self.subTest(escaped=escaped):
                token = guard.scan_swift_source('"' + escaped + '"')[0]
                self.assertEqual(token.decoded_literal, text)
                self.assertTrue(guard.has_han(token))
        for text in ("", "ASCII", "かな", "한글", "😀", r'\u{1F600}'):
            self.assertFalse(guard.has_han(guard.scan_swift_source('"' + text + '"')[0]))

    def test_unicode_and_escaped_backslash_do_not_relex_interpolation(self):
        token = guard.scan_swift_source(r'"\u{5c}(name) \\(name) \u{22}"')[0]
        self.assertEqual(token.literal_parts, ('\\(name) \\(name) "',))
        self.assertEqual(token.holes, ())

    def test_interpolation_offsets_tree_and_same_token_api(self):
        source = r'"a\("中")b"'
        outer, child = guard.scan_swift_source(source)
        self.assertEqual((outer.start, outer.end, outer.line, outer.column), (0, 10, 1, 1))
        self.assertEqual((child.start, child.end, child.line, child.column), (4, 7, 1, 5))
        self.assertEqual(outer.literal_parts, ("a", "b"))
        hole = outer.holes[0]
        self.assertEqual((hole.kind, hole.start, hole.end, hole.expression_start, hole.expression_end),
                         ("expression", 2, 8, 4, 7))
        self.assertEqual(hole.expression_source, '"中"')
        self.assertIs(hole.children[0], child)
        self.assertIs(outer.children[0], child)
        self.assertEqual(outer.raw_source, source)
        self.assertEqual(outer.decoded_literal, "ab")
        self.assertEqual([guard.has_han(t) for t in (outer, child)], [False, True])

    def test_three_level_nested_strings_and_interpolations_do_not_drop_han(self):
        source = r'"a\("b\("中")c")d"'
        tokens = guard.scan_swift_source(source)
        self.assertEqual([t.literal_parts for t in tokens], [("a", "d"), ("b", "c"), ("中",)])
        self.assertEqual([guard.has_han(t) for t in tokens], [False, False, True])
        self.assertEqual(tokens[0].children, (tokens[1],))
        self.assertEqual(tokens[1].children, (tokens[2],))

    def test_interpolation_handles_comments_closures_and_balanced_delimiters(self):
        source = '''"""
pre\\(call(/* ) "假" /* nested */ */ ["键": { _ in "值" }]) // ) "旁注"
)post
"""'''
        tokens = guard.scan_swift_source(source)
        self.assertEqual([t.source for t in tokens[1:]], ['"键"', '"值"'])
        self.assertEqual(tokens[0].literal_parts, ("pre", "post"))
        self.assertFalse(guard.has_han(tokens[0]))

    def test_chinese_identifiers_are_expression_not_literal_text(self):
        token = guard.scan_swift_source(r'"value \(中文变量) $plain"')[0]
        self.assertEqual(token.literal_parts, ("value ", " $plain"))
        self.assertEqual(token.holes[0].expression_source, "中文变量")
        self.assertFalse(guard.has_han(token))
        # Swift's dollar/braces are ordinary text, unlike Kotlin templates.
        swift = guard.scan_swift_source('"${中文变量}"')[0]
        self.assertEqual(swift.literal_parts, ("${中文变量}",))
        self.assertTrue(guard.has_han(swift))
        self.assertFalse(guard.has_han(guard.scan_source('"${中文变量}"')[0]))

    def test_adjacent_interpolations_preserve_empty_segments(self):
        token = guard.scan_swift_source(r'"\(a)\(b)\(c)"')[0]
        self.assertEqual(token.literal_parts, ("", "", "", ""))
        self.assertEqual([h.expression_source for h in token.holes], ["a", "b", "c"])

    def test_hash_delimiters_control_escapes_and_interpolations(self):
        inactive = guard.scan_swift_source(r'#"\u{4E2D} \(name)"#')[0]
        self.assertEqual(inactive.kind, "raw_string")
        self.assertEqual(inactive.literal_parts, (r'\u{4E2D} \(name)',))
        self.assertEqual(inactive.holes, ())
        self.assertFalse(guard.has_han(inactive))
        active = guard.scan_swift_source(r'##"\##u{20000} \##("内")"##')
        self.assertEqual([t.literal_parts for t in active], [("\U00020000 ", ""), ("内",)])
        self.assertEqual([t.kind for t in active], ["raw_string", "string"])
        self.assertEqual([guard.has_han(t) for t in active], [True, True])

    def test_raw_quotes_backslashes_and_mixed_nested_raw_literals(self):
        token = guard.scan_swift_source(r'#"\#" \#\ \#u{4E2D}"#')[0]
        self.assertEqual(token.literal_parts, ('" \\ 中',))
        tokens = guard.scan_swift_source(r'##"a\##(#"b\#("中")c"#)d"##')
        self.assertEqual([t.kind for t in tokens], ["raw_string", "raw_string", "string"])
        self.assertEqual([t.literal_parts for t in tokens], [("a", "d"), ("b", "c"), ("中",)])

    def test_unindented_multiline_and_interpolation(self):
        source = '"""\n前\n\\(value)\n后\n"""'
        token = guard.scan_swift_source(source)[0]
        self.assertEqual(token.kind, "string")
        self.assertEqual(token.literal_parts, ("前\n", "\n后"))
        self.assertTrue(guard.has_han(token))
        self.assertEqual(guard.scan_swift_source('"""\n"""')[0].literal_parts, ("",))

    def test_raw_multiline_crlf_preserves_source_and_decodes_newlines(self):
        source = '#"""\r\n\\#u{4E2D}\r\n\\#("内")\r\n"""#'
        outer, child = guard.scan_swift_source(source)
        self.assertEqual(outer.literal_parts, ("中\n", ""))
        self.assertEqual(outer.raw_source, source)
        self.assertEqual(outer.kind, "raw_string")
        self.assertEqual((child.line, child.column), (3, 4))
        self.assertTrue(guard.has_han(child))

    def test_crlf_lone_cr_and_empty_thin_swift_file(self):
        tokens = guard.scan_swift_source('let a="ASCII"\r\nlet b="中"\rlet c=#"文"#')
        self.assertEqual([(t.line, t.column) for t in tokens], [(1, 7), (2, 7), (3, 7)])
        self.assertEqual(guard.scan_swift_source('import SwiftUI\n@main struct App { var body: View { ContentView() } }'), [])

    def test_unknown_escapes_unicode_and_complex_forms_fail_explicitly(self):
        sources = [r'"\u4E2D"', r'"\u{}"', r'"\u{GG}"', r'"\u{123456789}"',
                   r'"\u{110000}"', r'"\u{D800}"', r'"\x41"', r'"\q"', '"bad\nline"',
                   '"open', '#"open', '##"中文"#', '#notAString', '#if DEBUG\n"中文"\n#endif',
                   '/中文/', '#/中文/#', "'中'", '/* open', '*/', 'let x = 😀',
                   r'"\()"', r'"\(/* only */)"', r'"\(call(])"', r'"\(value"',
                   '"""inline"""', '"""\n中\n    """', '"""\n中\\\n"""',
                   r'#"\#q"#', '"""\n中\n""""', 'let x = \\u{4E2D}']
        for source in sources:
            with self.subTest(source=source):
                with self.assertRaises(guard.LexicalError) as raised:
                    guard.scan_swift_source(source)
                self.assertTrue(raised.exception.message)

    def test_unknown_nested_forms_fail_instead_of_skipping_children(self):
        for source in (r'"\(call(/中文/))"', r'"\(#"\#q中文"#)"',
                       '"\\("""\n中文\n  """)"', r'"\("\u{D800}中文")"'):
            with self.subTest(source=source), self.assertRaises(guard.LexicalError):
                guard.scan_swift_source(source)

    def test_error_locations_are_explicit(self):
        with self.assertRaises(guard.LexicalError) as raised:
            guard.scan_swift_source('let a=1\r\nlet b="bad\n"')
        self.assertEqual((raised.exception.offset, raised.exception.line, raised.exception.column), (19, 2, 11))
        self.assertIn("Swift", raised.exception.message)

    def test_excessive_nested_interpolation_fails_with_lexical_error(self):
        source = '"中"'
        for _ in range(105):
            source = '"\\(' + source + ')"'
        with self.assertRaises(guard.LexicalError):
            guard.scan_swift_source(source)

    def test_swift_source_type_is_not_implicitly_converted(self):
        with self.assertRaises(TypeError):
            guard.scan_swift_source(b'"ASCII"')


class GuardExceptionTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="display-text-guard-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.ui_path = "shared/src/commonMain/kotlin/io/github/betterzslf/ui/Sample.kt"
        self.app_path = "shared/src/commonMain/kotlin/io/github/betterzslf/App.kt"
        self.allowlist = self.root / "scripts/display-text-exceptions.json"
        for name in ("core", "feature", "data", "platform"):
            (self.root / "shared/src/commonMain/kotlin/io/github/betterzslf" / name).mkdir(parents=True)
        for prefix, _ in PHASE_C_ROOTS:
            (self.root / prefix).mkdir(parents=True)
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

    def program_fixture(self, name="RunMapBootstrapPolicy.kt"):
        path = "shared/src/commonMain/kotlin/io/github/betterzslf/platform/" + name
        padding = "//" + "p" * 80 + "\n"
        if name == "RunMapWeb.kt":
            source = (padding + "fun document() {\n        val html = " + RUN_MAP_HTML
                      + "\n        return RunMapDocument(html, null)\n    }\n")
        else:
            self.assertEqual(name, "RunMapBootstrapPolicy.kt")
            source = (padding + "internal object RunMapBootstrapPolicy {\n    const val emptyDocument = "
                      + EMPTY_MAP_DOCUMENT + '\n    private val nonce = Regex("[A-Za-z0-9_-]{1,128}")\n}\n')
        self.write(self.ui_path, 'val label = "ASCII"\n')
        self.write(path, source)
        return path, source, guard.scan_source(source)[0]

    def program_entry(self, path, source, token):
        return guard.make_exception(path, source, token, "Phase C embedded program migration debt",
                                    "task39/text-migration", category="deferred_program")

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
        # Keep the legacy outside-scope regression; C now includes native Main roots.
        self.write(self.ui_path, 'val label = "ASCII"\n')
        outside = ["shared/src/commonMain/kotlin/io/github/betterzslf/core/text/AppText.kt",
                   "shared/src/commonTest/kotlin/io/github/betterzslf/ui/Bad.kt",
                   "shared/src/androidTest/kotlin/io/github/betterzslf/ui/Bad.kt",
                   "shared/src/iosTest/kotlin/io/github/betterzslf/platform/Bad.kt",
                   "shared/src/commonMain/kotlin/io/github/betterzslf/other/Bad.kt",
                   "tests/ui/Bad.kt", "deps/ui/Bad.kt"]
        for path in outside:
            self.write(path, '$$"""中文 unsupported ignored outside scope"""')
        result = self.run_guard()
        self.assertTrue(result.ok, result.to_dict())
        self.assertEqual(result.files_scanned, 2)
        self.write("shared/src/commonMain/kotlin/io/github/betterzslf/ui/nested/Child.kt", 'val x = "界面"')
        self.write("shared/src/commonMain/kotlin/io/github/betterzslf/core/Other.kt", 'val x = "核心"')
        self.write("shared/src/commonMain/kotlin/io/github/betterzslf/feature/B.kt", 'val x = "功能"')
        result = self.run_guard()
        self.assertEqual(result.files_scanned, 5)
        self.assertEqual([v["source"] for v in result.violations], ['"界面"', '"核心"', '"功能"'])

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

    def test_phase_b_directories_and_optional_graph_are_scanned(self):
        self.write(self.ui_path, "val label = Unit\n")
        paths = ["shared/src/commonMain/kotlin/io/github/betterzslf/" + suffix for suffix in (
            "core/nested/Core.kt", "feature/nested/Feature.kt", "data/nested/Data.kt",
            "platform/nested/Platform.kt", "AppGraph.kt")]
        for path in paths:
            self.write(path, 'val label = "显示"\n')
        result = self.run_guard()
        self.assertEqual(result.files_scanned, 7)
        self.assertEqual({v["path"] for v in result.violations}, set(paths))
        self.assertEqual(result.errors, ())

    def test_only_exact_catalog_file_is_excluded_not_its_directory(self):
        self.write(self.ui_path, "val label = Unit\n")
        catalog = "shared/src/commonMain/kotlin/io/github/betterzslf/core/text/AppText.kt"
        self.write(catalog, '$$"""unsupported catalog syntax is excluded"""')
        siblings = ["shared/src/commonMain/kotlin/io/github/betterzslf/core/text/" + suffix
                    for suffix in ("Other.kt", "nested/AppText.kt", "AppTextExtra.kt")]
        for path in siblings:
            self.write(path, 'val label = "同目录也受守卫"\n')
        result = self.run_guard()
        self.assertEqual(result.files_scanned, 5)
        self.assertEqual({v["path"] for v in result.violations}, set(siblings))
        self.assertEqual(result.errors, ())

    def test_app_graph_is_optional_but_unknown_lexical_forms_fail_when_present(self):
        self.write(self.ui_path, "val label = Unit\n")
        self.assertTrue(self.run_guard().ok)
        graph = "shared/src/commonMain/kotlin/io/github/betterzslf/AppGraph.kt"
        self.write(graph, '$$"""未知形式"""')
        result = self.run_guard()
        self.assertFalse(result.ok)
        self.assertEqual([(e["code"], e["path"]) for e in result.errors], [("lexical_error", graph)])

    def test_phase_b_missing_directories_fail_closed(self):
        self.write(self.ui_path, "val label = Unit\n")
        for name in ("core", "feature", "data", "platform"):
            path = "shared/src/commonMain/kotlin/io/github/betterzslf/" + name + "/"
            (self.root / path).rmdir()
            result = self.run_guard()
            self.assertFalse(result.ok)
            self.assertIn(("scope_error", path), [(e["code"], e["path"]) for e in result.errors])
            (self.root / path).mkdir()

    def test_phase_b_unknown_lexical_forms_fail_in_each_new_directory(self):
        for name in ("core", "feature", "data", "platform"):
            with self.subTest(directory=name):
                path = "shared/src/commonMain/kotlin/io/github/betterzslf/" + name + "/Unknown.kt"
                self.write(path, '$$"""未知形式"""')
                result = self.run_guard()
                self.assertIn(("lexical_error", path), [(e["code"], e["path"]) for e in result.errors])

    def test_phase_b_factory_accepts_exact_paths_and_retains_canonical_checks(self):
        for suffix in ("core/C.kt", "core/text/Sibling.kt", "feature/F.kt", "data/D.kt", "platform/P.kt", "AppGraph.kt"):
            path = "shared/src/commonMain/kotlin/io/github/betterzslf/" + suffix
            self.assertEqual(guard.make_exception(path, self.source, guard.scan_source(self.source)[0],
                                                 "Protocol value", "fixture")["path"], path)
        for suffix in ("core//C.kt", "core/./C.kt", "core/../C.kt", "core/text/AppText.kt", "AppGraph.kt/", "AppGraph.kts"):
            with self.subTest(suffix=suffix), self.assertRaises(ValueError):
                guard.make_exception("shared/src/commonMain/kotlin/io/github/betterzslf/" + suffix,
                                     self.source, guard.scan_source(self.source)[0], "Protocol value", "fixture")

    def test_both_exact_deferred_program_tokens_pass_without_protocol_misclassification(self):
        entries = []
        for name, kind, fingerprint in (
            ("RunMapWeb.kt", "raw_string", "40441487499900bac6eca619e3ba22369912c0948954682d996b1b1f7a4e6057"),
            ("RunMapBootstrapPolicy.kt", "string", "540832e14e9a9199553d34978b66b6ec732a89235e733fb8c5c993bf107ec3dc"),
        ):
            path, source, token = self.program_fixture(name)
            self.assertEqual(token.kind, kind)
            self.assertEqual(hashlib.sha256(token.source.encode("utf-8")).hexdigest(), fingerprint)
            self.assertTrue(guard.has_han(token))
            self.assertIn("<!doctype html>", token.decoded_literal)
            entries.append(self.program_entry(path, source, token))
        self.allow(entries)
        result = self.run_guard()
        self.assertTrue(result.ok, result.to_dict())
        self.assertEqual((result.han_tokens, result.exempted_tokens, result.deferred_tokens), (2, 2, 2))
        self.assertEqual((result.protocol_tokens, result.lookup_tokens), (0, 0))

    def test_known_programs_are_violations_without_explicit_entries(self):
        paths = {self.program_fixture(name)[0] for name in ("RunMapWeb.kt", "RunMapBootstrapPolicy.kt")}
        result = self.run_guard()
        self.assertEqual({v["path"] for v in result.violations}, paths)
        self.assertEqual((result.exempted_tokens, result.deferred_tokens), (0, 0))

    def test_deferred_program_and_outside_display_labels_in_same_files_are_separate(self):
        entries = []
        for name in ("RunMapWeb.kt", "RunMapBootstrapPolicy.kt"):
            path, source, token = self.program_fixture(name)
            source += 'val firstLabel = "外部显示"; val secondLabel = "第二显示"\n'
            self.write(path, source)
            token = guard.scan_source(source)[0]
            entries.append(self.program_entry(path, source, token))
        self.allow(entries)
        result = self.run_guard()
        self.assertEqual(result.deferred_tokens, 2)
        self.assertEqual(result.exempted_tokens, 2)
        self.assertEqual(sorted(v["source"] for v in result.violations), ['"外部显示"'] * 2 + ['"第二显示"'] * 2)
        self.assertFalse(result.ok)

    def test_deferred_category_rejects_arbitrary_display_in_both_known_paths(self):
        for name, literal in (("RunMapWeb.kt", '"""任意显示"""'), ("RunMapBootstrapPolicy.kt", '"任意显示"')):
            path, source, token = self.program_fixture(name)
            changed = source.replace(token.source, literal)
            self.write(path, changed)
            display = guard.scan_source(changed)[0]
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.program_entry(path, changed, display)
            entry = guard.make_exception(path, changed, display, "Display fixture", "fixture")
            entry["category"] = "deferred_program"
            self.allow([entry])
            result = self.run_guard()
            self.assertEqual(result.errors[0]["code"], "allowlist_error")
            self.assertEqual(result.deferred_tokens, 0)
            self.assertIn(literal, [v["source"] for v in result.violations])

    def test_deferred_program_exact_path_prevents_rebinding_and_name_aliases(self):
        path, source, token = self.program_fixture("RunMapWeb.kt")
        for suffix in ("ui/RunMapWeb.kt", "platform/nested/RunMapWeb.kt", "platform/RunMapWebCopy.kt",
                       "platform/runMapWeb.kt", "platform/RunMapBootstrapPolicy.kt", "AppGraph.kt"):
            target = "shared/src/commonMain/kotlin/io/github/betterzslf/" + suffix
            with self.subTest(target=target), self.assertRaises(ValueError):
                self.program_entry(target, source, token)
        entry = self.program_entry(path, source, token)
        entry["path"] = "shared/src/commonMain/kotlin/io/github/betterzslf/platform/RunMapWebCopy.kt"
        self.allow([entry])
        self.assertEqual(self.run_guard().errors[0]["code"], "allowlist_error")

    def test_deferred_program_kind_is_exact_in_factory_and_document(self):
        path, source, token = self.program_fixture("RunMapWeb.kt")
        entry = self.program_entry(path, source, token)
        entry["kind"] = "string"
        self.allow([entry])
        result = self.run_guard()
        self.assertEqual(result.errors[0]["code"], "allowlist_error")
        self.assertEqual(result.deferred_tokens, 0)
        quoted = '"' + token.source[3:-3].replace('"', '\\"').replace("\n", "\\n") + '"'
        changed = source.replace(token.source, quoted)
        with self.assertRaises(ValueError):
            self.program_entry(path, changed, guard.scan_source(changed)[0])

    def test_deferred_program_source_edits_invalidate_entry_and_regeneration(self):
        for name, old, new in (("RunMapWeb.kt", "路线地图", "另一地图"),
                               ("RunMapBootstrapPolicy.kt", "地图暂不可用", "地图暂未就绪")):
            path, source, token = self.program_fixture(name)
            self.allow([self.program_entry(path, source, token)])
            changed = source.replace(old, new)
            self.write(path, changed)
            result = self.run_guard()
            self.assertEqual(result.errors[0]["code"], "exception_match_error")
            self.assertEqual(result.deferred_tokens, 0)
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.program_entry(path, changed, guard.scan_source(changed)[0])

    def test_deferred_program_ascii_code_changes_and_extra_han_are_not_generic_program_passes(self):
        path, source, token = self.program_fixture("RunMapWeb.kt")
        for literal in (token.source.replace("ready=false", "ready=true"),
                        token.source.replace("</body>", '<p>新增显示</p></body>'),
                        '"""<!doctype html><script>function map(){}</script><p>新程序</p>"""'):
            changed = source.replace(token.source, literal)
            with self.subTest(literal=literal[:80]), self.assertRaises(ValueError):
                self.program_entry(path, changed, guard.scan_source(changed)[0])

    def test_deferred_program_deletion_or_move_to_another_file_is_stale(self):
        path, source, token = self.program_fixture()
        self.allow([self.program_entry(path, source, token)])
        self.write(path, "val emptyDocument = Unit\n")
        self.write("shared/src/commonMain/kotlin/io/github/betterzslf/platform/Copy.kt", source)
        result = self.run_guard()
        self.assertEqual(result.errors[0]["code"], "exception_match_error")
        self.assertEqual((result.exempted_tokens, result.deferred_tokens), (0, 0))
        self.assertEqual(len(result.violations), 1)

    def test_deferred_program_full_context_changes_fail(self):
        path, source, token = self.program_fixture()
        self.allow([self.program_entry(path, source, token)])
        self.write(path, source.replace("Policy {", "Policz {"))
        result = self.run_guard()
        self.assertEqual(result.errors[0]["code"], "exception_match_error")
        self.assertEqual(result.deferred_tokens, 0)

    def test_deferred_program_moves_outside_context_fail_all_position_fields(self):
        path, source, token = self.program_fixture("RunMapWeb.kt")
        entry = self.program_entry(path, source, token)
        self.allow([entry])
        self.write(path, "\n" + source)
        result = self.run_guard()
        self.assertEqual(result.errors[0]["code"], "exception_position_error")
        self.assertEqual(result.deferred_tokens, 0)
        self.write(path, source)
        for name in ("start", "end", "line", "column"):
            moved = copy.deepcopy(entry)
            moved["position"][name] += 1
            self.allow([moved])
            self.assertEqual(self.run_guard().errors[0]["code"], "exception_position_error")

    def test_deferred_program_duplicate_contexts_fail_even_with_position(self):
        path, source, token = self.program_fixture()
        entry = self.program_entry(path, source, token)
        self.write(path, source + source)
        self.allow([entry])
        result = self.run_guard()
        self.assertEqual(result.errors[0]["code"], "exception_match_error")
        self.assertIn("found 2", result.errors[0]["message"])
        self.assertEqual((result.exempted_tokens, result.deferred_tokens), (0, 0))
        self.assertEqual(len(result.violations), 2)

    def test_deferred_program_second_copy_with_different_context_is_also_rejected(self):
        path, source, token = self.program_fixture("RunMapWeb.kt")
        entry = self.program_entry(path, source, token)
        self.write(path, source + "val duplicate = " + token.source + "\n")
        self.allow([entry])
        result = self.run_guard()
        self.assertEqual(result.errors[0]["code"], "exception_match_error")
        self.assertIn("found 2", result.errors[0]["message"])
        self.assertEqual(result.deferred_tokens, 0)
        self.assertEqual(len(result.violations), 2)

    def test_deferred_program_duplicate_entries_fail_without_counting_twice(self):
        path, source, token = self.program_fixture()
        entry = self.program_entry(path, source, token)
        self.allow([entry, copy.deepcopy(entry)])
        result = self.run_guard()
        self.assertEqual(result.errors[0]["code"], "exception_duplicate_error")
        self.assertEqual((result.exempted_tokens, result.deferred_tokens), (1, 1))
        self.assertFalse(result.ok)

    def test_known_html_programs_are_not_accepted_as_protocol_or_lookup(self):
        for name in ("RunMapWeb.kt", "RunMapBootstrapPolicy.kt"):
            path, source, token = self.program_fixture(name)
            for category in ("protocol", "lookup"):
                with self.subTest(name=name, category=category), self.assertRaises(ValueError):
                    guard.make_exception(path, source, token, "Not a protocol value", "fixture", category=category)
                entry = self.program_entry(path, source, token)
                entry["category"] = category
                self.allow([entry])
                self.assertEqual(self.run_guard().errors[0]["code"], "allowlist_error")

    def test_deferred_program_declaration_context_cannot_be_rebound(self):
        for name, old, new in (("RunMapWeb.kt", "val html = ", "val label = "),
                               ("RunMapBootstrapPolicy.kt", "const val emptyDocument = ", "const val label = ")):
            path, source, token = self.program_fixture(name)
            changed = source.replace(old, new)
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.program_entry(path, changed, guard.scan_source(changed)[0])

    def test_deferred_entry_retains_schema_reason_owner_and_position_validation(self):
        path, source, token = self.program_fixture()
        baseline = self.program_entry(path, source, token)
        for mutation in ({"owner": " "}, {"reason": ""}, {"wholeFile": True},
                         {"position": {"start": False, "end": 1, "line": 1, "column": 1}},
                         {"context": {"before": "x" * 49, "after": ""}}, {"kind": "line"}):
            entry = copy.deepcopy(baseline)
            entry.update(mutation)
            self.allow([entry])
            self.assertEqual(self.run_guard().errors[0]["code"], "allowlist_error")
            self.assertEqual(self.run_guard().deferred_tokens, 0)

    def test_category_counts_are_separate_and_exempted_total_is_compatible(self):
        path, source, token = self.program_fixture()
        entries = [self.program_entry(path, source, token)]
        ui_source = 'val protocol = "协议"; val lookup = "检索"\n'
        self.write(self.ui_path, ui_source)
        entries.extend([self.entry(ui_source, 0), self.entry(ui_source, 1, category="lookup")])
        self.allow(entries)
        result = self.run_guard()
        self.assertTrue(result.ok, result.to_dict())
        self.assertEqual((result.protocol_tokens, result.lookup_tokens, result.deferred_tokens), (1, 1, 1))
        self.assertEqual((result.han_tokens, result.exempted_tokens), (3, 3))
        report = result.to_dict()
        self.assertEqual([report[k] for k in ("protocol_tokens", "lookup_tokens", "deferred_tokens", "exempted_tokens")],
                         [1, 1, 1, 3])
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(guard.main(["--root", str(self.root)]), 0)
        self.assertIn("exceptions=3, protocol=1, lookup=1, deferred=1", output.getvalue())
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(guard.main(["--root", str(self.root), "--json"]), 0)
        self.assertEqual(json.loads(output.getvalue()), report)
        self.assertEqual(sorted(p.name for p in (self.root / "scripts").iterdir()), ["display-text-exceptions.json"])

    def test_phase_b_protocol_and_display_on_same_line_are_not_blanket_exempted(self):
        self.write(self.ui_path, "val label = Unit\n")
        entries = []
        paths = []
        source = 'val protocol = "协议"; val display = "显示"\n'
        for name in ("core", "feature", "data", "platform"):
            path = "shared/src/commonMain/kotlin/io/github/betterzslf/" + name + "/Pair.kt"
            paths.append(path)
            self.write(path, source)
            entries.append(guard.make_exception(path, source, guard.scan_source(source)[0],
                                                "Exact protocol value, not its display label", "fixture"))
        self.allow(entries)
        result = self.run_guard()
        self.assertEqual((result.exempted_tokens, result.protocol_tokens, result.deferred_tokens), (4, 4, 0))
        self.assertEqual({v["path"] for v in result.violations}, set(paths))
        self.assertEqual([v["source"] for v in result.violations], ['"显示"'] * 4)
        self.assertFalse(result.ok)

    def test_guard_result_legacy_six_argument_constructor_still_works(self):
        result = guard.GuardResult(2, 3, 0, 0, (), ())
        self.assertTrue(result.ok)
        self.assertEqual((result.protocol_tokens, result.lookup_tokens, result.deferred_tokens), (0, 0, 0))
        self.assertEqual(result.to_dict()["exempted_tokens"], 0)

    def diagnostic_fixture(self):
        self.assertEqual(hashlib.sha256(DIAGNOSTIC_FIXTURE.encode("utf-8")).hexdigest(), DIAGNOSTIC_SOURCE_SHA256)
        self.write(self.ui_path, 'val label = "ASCII"\n')
        self.write(DIAGNOSTIC_PATH, DIAGNOSTIC_FIXTURE)
        tokens = [t for t in guard.scan_source(DIAGNOSTIC_FIXTURE) if guard.has_han(t)]
        self.assertEqual([t.source for t in tokens], ['"synthetic-中文"'] * 2)
        entries = [guard.make_exception(DIAGNOSTIC_PATH, DIAGNOSTIC_FIXTURE, token,
                                       "Original synthetic Unicode roundtrip data, not display or wire", "fixture",
                                       category="diagnostic_fixture") for token in tokens]
        return entries

    def test_phase_c_every_root_scans_descendants_outside_the_common_package(self):
        self.write(self.ui_path, "val label = Unit\n")
        paths = []
        for prefix, extension in PHASE_C_ROOTS:
            path = prefix + "nested/another/package/Display." + extension
            paths.append(path)
            self.write(path, 'let label = "显示"\n' if extension == "swift" else 'val label = "显示"\n')
        result = self.run_guard()
        self.assertEqual(result.files_scanned, 7)
        self.assertEqual({v["path"] for v in result.violations}, set(paths))
        self.assertEqual([v["source"] for v in result.violations], ['"显示"'] * 5)
        self.assertEqual(result.errors, ())

    def test_phase_c_missing_directory_or_directory_replaced_by_file_fails_closed(self):
        self.write(self.ui_path, "val label = Unit\n")
        for prefix, _ in PHASE_C_ROOTS:
            with self.subTest(prefix=prefix):
                path = self.root / prefix
                path.rmdir()
                self.assertIn(("scope_error", prefix), [(e["code"], e["path"]) for e in self.run_guard().errors])
                path.write_text("not a directory", encoding="utf-8")
                self.assertIn(("scope_error", prefix), [(e["code"], e["path"]) for e in self.run_guard().errors])
                path.unlink()
                path.mkdir()
        self.assertTrue(self.run_guard().ok)

    def test_phase_c_protocol_display_on_same_line_in_all_five_roots(self):
        self.write(self.ui_path, "val label = Unit\n")
        entries, paths = [], []
        for prefix, extension in PHASE_C_ROOTS:
            path = prefix + "Pair." + extension
            source = ('let' if extension == 'swift' else 'val') + ' key = "协议"; Text("按钮")\n'
            scanner = guard.scan_swift_source if extension == 'swift' else guard.scan_source
            self.write(path, source)
            entries.append(guard.make_exception(path, source, scanner(source)[0], "Exact protocol value", "fixture"))
            paths.append(path)
        self.allow(entries)
        result = self.run_guard()
        self.assertEqual((result.han_tokens, result.exempted_tokens, result.protocol_tokens), (10, 5, 5))
        self.assertEqual({v["path"] for v in result.violations}, set(paths))
        self.assertEqual([v["source"] for v in result.violations], ['"按钮"'] * 5)
        self.assertEqual(result.errors, ())

    def test_phase_c_unknown_lexical_forms_fail_in_every_root(self):
        self.write(self.ui_path, "val label = Unit\n")
        for prefix, extension in PHASE_C_ROOTS:
            with self.subTest(prefix=prefix):
                path = prefix + "Unknown." + extension
                self.write(path, '/中文/' if extension == 'swift' else '$$"""中文"""')
                result = self.run_guard()
                self.assertFalse(result.ok)
                self.assertIn(("lexical_error", path), [(e["code"], e["path"]) for e in result.errors])
                (self.root / path).unlink()

    def test_phase_c_only_the_original_common_catalog_is_excluded(self):
        self.write(self.ui_path, "val label = Unit\n")
        self.write("shared/src/commonMain/kotlin/io/github/betterzslf/core/text/AppText.kt", '$$"""catalog excluded"""')
        paths = []
        for prefix, extension in PHASE_C_ROOTS:
            path = prefix + "io/github/betterzslf/core/text/AppText." + extension
            paths.append(path)
            self.write(path, '"同名文件仍受守卫"')
        result = self.run_guard()
        self.assertEqual({v["path"] for v in result.violations}, set(paths))
        self.assertEqual(result.files_scanned, 7)
        self.assertEqual(result.errors, ())

    def test_phase_c_resources_tests_and_wrong_language_extensions_stay_outside(self):
        self.write(self.ui_path, "val label = Unit\n")
        paths = ("shared/src/androidTest/kotlin/Outside.kt", "shared/src/iosTest/kotlin/Outside.kt",
                 "shared/src/jvmTest/kotlin/Outside.kt", "androidApp/src/test/kotlin/Outside.kt",
                 "androidApp/src/main/AndroidManifest.xml", "androidApp/src/main/res/values/strings.xml",
                 "iosApp/Tests/Outside.swift", "iosApp/Sources/Outside.kt",
                 "shared/src/androidMain/kotlin/Outside.swift", "shared/src/iosMain/resources/Outside.kt")
        for path in paths:
            self.write(path, '$$"""中文 unsupported outside scope"""')
        result = self.run_guard()
        self.assertTrue(result.ok, result.to_dict())
        self.assertEqual(result.files_scanned, 2)

    def test_phase_c_exception_factory_accepts_new_roots_and_keeps_canonical_paths(self):
        source = '"协议"'
        token = guard.scan_source(source)[0]
        for prefix, extension in PHASE_C_ROOTS:
            path = prefix + "nested/Pair." + extension
            with self.subTest(path=path):
                self.assertEqual(guard.make_exception(path, source, token, "Protocol", "fixture")["path"], path)
                for suffix in ("/", "s", "/../Pair." + extension):
                    with self.assertRaises(ValueError):
                        guard.make_exception(path + suffix, source, token, "Protocol", "fixture")
                with self.assertRaises(ValueError):
                    guard.make_exception(prefix + "nested//Pair." + extension, source, token, "Protocol", "fixture")
        for path in ("iosApp/Sources/Pair.kt", "androidApp/src/main/kotlin/Pair.swift", "iosApp/Tests/Pair.swift"):
            with self.assertRaises(ValueError):
                guard.make_exception(path, source, token, "Protocol", "fixture")

    def test_phase_c_swift_unicode_and_nested_strings_are_all_guarded(self):
        self.write(self.ui_path, "val label = Unit\n")
        path = "iosApp/Sources/Nested.swift"
        self.write(path, r'let label = "\u{4E2D}"; Text("\(ok ? "内" : #"外"#)")')
        result = self.run_guard()
        self.assertEqual(result.errors, ())
        self.assertEqual(result.han_tokens, 3)
        self.assertEqual([v["source"] for v in result.violations], [r'"\u{4E2D}"', '"内"', '#"外"#'])
        self.assertEqual([v["kind"] for v in result.violations], ["string", "string", "raw_string"])
        self.assertTrue(all(v["path"] == path for v in result.violations))

    def test_phase_c_swift_exceptions_match_exact_path_kind_source_context_and_position(self):
        self.write(self.ui_path, "val label = Unit\n")
        path = "iosApp/Sources/Pair.swift"
        source = r'let key = #"\#u{4E2D}"#; Text("显示")' + '\n'
        self.write(path, source)
        token = guard.scan_swift_source(source)[0]
        entry = guard.make_exception(path, source, token, "Exact protocol", "fixture")
        self.allow([entry])
        result = self.run_guard()
        self.assertEqual((result.exempted_tokens, result.protocol_tokens), (1, 1))
        self.assertEqual([v["source"] for v in result.violations], ['"显示"'])
        self.assertEqual(entry["kind"], "raw_string")
        mutations = ({"path": "iosApp/Sources/Copy.swift"}, {"kind": "string"}, {"source": '#"另一个"#'},
                     {"context": {"before": "let other = ", "after": entry["context"]["after"]}})
        for mutation in mutations:
            changed = copy.deepcopy(entry)
            changed.update(mutation)
            self.allow([changed])
            with self.subTest(mutation=mutation):
                result = self.run_guard()
                self.assertEqual(result.errors[0]["code"], "exception_match_error")
                self.assertEqual(result.exempted_tokens, 0)
        for field in ("start", "end", "line", "column"):
            changed = copy.deepcopy(entry)
            changed["position"][field] += 1
            self.allow([changed])
            self.assertEqual(self.run_guard().errors[0]["code"], "exception_position_error")

    def test_phase_c_swift_moved_deleted_duplicate_and_stale_entries_fail(self):
        self.write(self.ui_path, "val label = Unit\n")
        path = "iosApp/Sources/Protocol.swift"
        source = "//" + "p" * 80 + '\nlet key = "协议"\n' + "//" + "q" * 80 + "\n"
        entry = guard.make_exception(path, source, guard.scan_swift_source(source)[0], "Exact protocol", "fixture")
        self.allow([entry])
        self.write(path, "\n" + source)
        self.assertEqual(self.run_guard().errors[0]["code"], "exception_position_error")
        self.write(path, source + source)
        self.assertEqual(self.run_guard().errors[0]["code"], "exception_match_error")
        self.write(path, source.replace("协议", "新协议"))
        self.assertEqual(self.run_guard().errors[0]["code"], "exception_match_error")
        self.write(path, source)
        self.allow([entry, copy.deepcopy(entry)])
        self.assertEqual(self.run_guard().errors[0]["code"], "exception_duplicate_error")
        (self.root / path).unlink()
        self.assertEqual(self.run_guard().errors[0]["code"], "exception_match_error")

    def test_phase_c_swift_unknown_nested_form_is_a_located_repository_error(self):
        self.write(self.ui_path, "val label = Unit\n")
        path = "iosApp/Sources/Unknown.swift"
        self.write(path, 'let a=1\r\nlet b="\\(call(/中文/))"')
        result = self.run_guard()
        self.assertFalse(result.ok)
        self.assertEqual(result.violations, ())
        self.assertEqual((result.errors[0]["code"], result.errors[0]["path"], result.errors[0]["line"]),
                         ("lexical_error", path, 2))
        self.assertIn("Swift", result.errors[0]["message"])

    def test_phase_c_diagnostic_exact_pair_is_separate_from_wire_or_deferred(self):
        entries = self.diagnostic_fixture()
        self.allow(entries)
        result = self.run_guard()
        self.assertTrue(result.ok, result.to_dict())
        self.assertEqual((result.han_tokens, result.exempted_tokens, result.diagnostic_tokens), (2, 2, 2))
        self.assertEqual((result.protocol_tokens, result.lookup_tokens, result.deferred_tokens), (0, 0, 0))
        self.assertEqual(result.to_dict()["diagnostic_tokens"], 2)

    def test_phase_c_diagnostic_pair_requires_two_exact_entries(self):
        entries = self.diagnostic_fixture()
        result = self.run_guard()
        self.assertEqual([v["source"] for v in result.violations], ['"synthetic-中文"'] * 2)
        self.allow(entries[:1])
        result = self.run_guard()
        self.assertEqual((result.exempted_tokens, result.diagnostic_tokens), (1, 1))
        self.assertEqual(len(result.violations), 1)
        self.assertEqual(result.violations[0]["start"], entries[1]["position"]["start"])

    def test_phase_c_diagnostic_is_not_a_whole_file_or_value_exemption(self):
        entries = self.diagnostic_fixture()
        source = DIAGNOSTIC_FIXTURE + 'val display = "外部显示"; Text("synthetic-中文")\n'
        self.write(DIAGNOSTIC_PATH, source)
        self.allow(entries)
        result = self.run_guard()
        self.assertEqual((result.exempted_tokens, result.diagnostic_tokens), (0, 0))
        self.assertEqual([e["code"] for e in result.errors], ["diagnostic_source_error"] * 2)
        self.assertEqual([v["source"] for v in result.violations], ['"synthetic-中文"'] * 2 + ['"外部显示"', '"synthetic-中文"'])
        token = guard.scan_source(source)[-1]
        with self.assertRaises(ValueError):
            guard.make_exception(DIAGNOSTIC_PATH, source, token, "Not an original use site", "fixture",
                                 category="diagnostic_fixture")

    def test_phase_c_diagnostic_exact_path_source_and_kind_cannot_be_rebound(self):
        baseline = self.diagnostic_fixture()[0]
        mutations = ({"path": "shared/src/iosMain/kotlin/io/github/betterzslf/platform/Copy.kt"},
                     {"path": "shared/src/androidMain/kotlin/io/github/betterzslf/platform/IosStartupDiagnostics.kt"},
                     {"path": "iosApp/Sources/IosStartupDiagnostics.swift"}, {"kind": "raw_string"},
                     {"source": '"synthetic-另一值"'}, {"source": '"""synthetic-中文"""'},
                     {"source": r'"synthetic-\u4E2D\u6587"'})
        for mutation in mutations:
            entry = copy.deepcopy(baseline)
            entry.update(mutation)
            self.allow([entry])
            with self.subTest(mutation=mutation):
                self.assertEqual(self.run_guard().errors[0]["code"], "allowlist_error")
                self.assertEqual(self.run_guard().diagnostic_tokens, 0)
        token = next(t for t in guard.scan_source(DIAGNOSTIC_FIXTURE) if guard.has_han(t))
        with self.assertRaises(ValueError):
            guard.make_exception(self.ui_path, DIAGNOSTIC_FIXTURE, token, "Wrong path", "fixture",
                                 category="diagnostic_fixture")

    def test_phase_c_diagnostic_put_get_context_is_hard_pinned_even_on_regeneration(self):
        entries = self.diagnostic_fixture()
        mutations = (("first.put(\"utf8\",", "second.put(\"utf8\","),
                     ("first.get(\"utf8\")", "second.get(\"utf8\")"),
                     ('"utf8"', '"text"'), ('"UNICODE_VALUE"', '"DISPLAY_VALUE"'))
        for old, new in mutations:
            with self.subTest(old=old):
                source = DIAGNOSTIC_FIXTURE.replace(old, new)
                self.write(DIAGNOSTIC_PATH, source)
                self.allow(entries)
                self.assertFalse(self.run_guard().ok)
                rejected = 0
                for token in guard.scan_source(source):
                    if guard.has_han(token):
                        try:
                            guard.make_exception(DIAGNOSTIC_PATH, source, token, "Changed use site", "fixture",
                                                 category="diagnostic_fixture")
                        except ValueError:
                            rejected += 1
                self.assertGreater(rejected, 0)

    def test_phase_c_diagnostic_movement_and_all_four_position_fields_are_checked(self):
        entries = self.diagnostic_fixture()
        self.allow(entries)
        self.write(DIAGNOSTIC_PATH, "\n" + DIAGNOSTIC_FIXTURE)
        result = self.run_guard()
        self.assertEqual([e["code"] for e in result.errors], ["exception_position_error"] * 2)
        self.assertEqual(result.diagnostic_tokens, 0)
        self.write(DIAGNOSTIC_PATH, DIAGNOSTIC_FIXTURE)
        for field in ("start", "end", "line", "column"):
            changed = copy.deepcopy(entries)
            changed[0]["position"][field] += 1
            self.allow(changed)
            self.assertEqual(self.run_guard().errors[0]["code"], "exception_position_error")
            self.assertEqual(self.run_guard().diagnostic_tokens, 1)

    def test_phase_c_diagnostic_full_source_sha_pin_rejects_unrelated_changes(self):
        entries = self.diagnostic_fixture()
        self.allow(entries)
        source = DIAGNOSTIC_FIXTURE + "\n// changed outside the original token/context/position\n"
        self.write(DIAGNOSTIC_PATH, source)
        result = self.run_guard()
        self.assertEqual([e["code"] for e in result.errors], ["diagnostic_source_error"] * 2)
        self.assertEqual((result.exempted_tokens, result.diagnostic_tokens, len(result.violations)), (0, 0, 2))
        for token in guard.scan_source(source):
            if guard.has_han(token):
                with self.assertRaisesRegex(ValueError, "complete Native diagnostic source SHA"):
                    guard.make_exception(DIAGNOSTIC_PATH, source, token, "Changed diagnostic body", "fixture",
                                         category="diagnostic_fixture")

    def test_phase_c_diagnostic_duplicate_entries_or_ambiguous_contexts_fail(self):
        entries = self.diagnostic_fixture()
        self.allow(entries + [copy.deepcopy(entries[0])])
        result = self.run_guard()
        self.assertEqual(result.errors[0]["code"], "exception_duplicate_error")
        self.assertEqual((result.exempted_tokens, result.diagnostic_tokens), (2, 2))
        self.allow(entries)
        self.write(DIAGNOSTIC_PATH, DIAGNOSTIC_FIXTURE + DIAGNOSTIC_FIXTURE)
        result = self.run_guard()
        self.assertEqual([e["code"] for e in result.errors], ["exception_match_error"] * 2)
        self.assertTrue(all("found 2" in e["message"] for e in result.errors))
        self.assertEqual((result.exempted_tokens, result.diagnostic_tokens, len(result.violations)), (0, 0, 4))

    def test_phase_c_diagnostic_changed_deleted_or_moved_to_another_file_is_stale(self):
        entries = self.diagnostic_fixture()
        self.allow(entries)
        for source in (DIAGNOSTIC_FIXTURE.replace("synthetic-中文", "synthetic-new"),
                       DIAGNOSTIC_FIXTURE.replace('"synthetic-中文"', 'catalogValue'),
                       "val absent = Unit\n"):
            self.write(DIAGNOSTIC_PATH, source)
            with self.subTest(source=source):
                self.assertFalse(self.run_guard().ok)
                self.assertEqual(self.run_guard().diagnostic_tokens, 0)
        self.write("shared/src/iosMain/kotlin/io/github/betterzslf/platform/Copy.kt", DIAGNOSTIC_FIXTURE)
        self.assertEqual(len(self.run_guard().violations), 2)

    def test_phase_c_diagnostic_data_is_never_accepted_as_protocol_or_lookup(self):
        entries = self.diagnostic_fixture()
        tokens = [t for t in guard.scan_source(DIAGNOSTIC_FIXTURE) if guard.has_han(t)]
        for category in ("protocol", "lookup"):
            for token in tokens:
                with self.subTest(category=category), self.assertRaises(ValueError):
                    guard.make_exception(DIAGNOSTIC_PATH, DIAGNOSTIC_FIXTURE, token,
                                         "Not wire data", "fixture", category=category)
            changed = copy.deepcopy(entries)
            changed[0]["category"] = category
            self.allow(changed)
            self.assertEqual(self.run_guard().errors[0]["code"], "allowlist_error")

    def test_phase_c_diagnostic_preserves_schema_reason_owner_and_context_validation(self):
        baseline = self.diagnostic_fixture()[0]
        for mutation in ({"owner": " "}, {"reason": ""}, {"wholeFile": True},
                         {"context": {"before": "first.put(\"utf8\", ", "after": ")"}},
                         {"position": {"start": False, "end": 1, "line": 1, "column": 1}}):
            entry = copy.deepcopy(baseline)
            entry.update(mutation)
            self.allow([entry])
            self.assertEqual(self.run_guard().errors[0]["code"], "allowlist_error")

    def test_phase_c_four_categories_sum_to_total_and_diagnostic_default_is_trailing(self):
        entries = self.diagnostic_fixture()
        path, source, token = self.program_fixture()
        entries.append(self.program_entry(path, source, token))
        ui_source = 'val key = "协议"; val lookup = "检索"\n'
        self.write(self.ui_path, ui_source)
        entries.extend([self.entry(ui_source), self.entry(ui_source, 1, category="lookup")])
        self.allow(entries)
        result = self.run_guard()
        self.assertTrue(result.ok, result.to_dict())
        self.assertEqual((result.protocol_tokens, result.lookup_tokens, result.deferred_tokens, result.diagnostic_tokens),
                         (1, 1, 1, 2))
        self.assertEqual(result.exempted_tokens, 5)
        report = result.to_dict()
        self.assertEqual(report["exempted_tokens"], sum(report[k] for k in (
            "protocol_tokens", "lookup_tokens", "deferred_tokens", "diagnostic_tokens")))
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(guard.main(["--root", str(self.root), "--json"]), 0)
        self.assertEqual(json.loads(output.getvalue()), report)
        self.assertEqual(guard.GuardResult(2, 3, 0, 0, (), ()).diagnostic_tokens, 0)
        self.assertEqual(guard.GuardResult(2, 3, 0, 0, (), (), 0, 0, 0).diagnostic_tokens, 0)
        self.assertEqual(guard.GuardResult(2, 3, 2, 2, (), (), 0, 0, 0, 2).diagnostic_tokens, 2)


class PlatformRenderBytesTests(unittest.TestCase):
    """Restricted literal assembly only; never execute Kotlin, HTML or JS."""

    root = Path(__file__).resolve().parents[2]
    platform = "shared/src/commonMain/kotlin/io/github/betterzslf/platform/"

    def source(self, path):
        with (self.root / path).open(encoding="utf-8", newline="") as stream:
            return stream.read()

    def catalog_label(self, name):
        catalog = self.source("shared/src/commonMain/kotlin/io/github/betterzslf/core/text/AppText.kt")
        run = re.search(r"^    object Run \{\n(.*?)^    \}", catalog, re.M | re.S).group(1)
        matches = re.findall(r"^        const val " + re.escape(name) + r": String = ([^\r\n]+)$", run, re.M)
        self.assertEqual(len(matches), 1)
        tokens = guard.scan_source(matches[0])
        self.assertEqual(len(tokens), 1)
        self.assertEqual(tokens[0].kind, "string")
        self.assertEqual(tokens[0].holes, ())
        return tokens[0].decoded_literal

    def assemble(self, token, values):
        self.assertEqual(len(token.literal_parts), len(token.holes) + 1)
        chunks = [token.literal_parts[0]]
        for hole, part in zip(token.holes, token.literal_parts[1:]):
            self.assertIn(hole.expression_source, values)
            chunks.extend((values[hole.expression_source], part))
        return "".join(chunks).encode("utf-8")

    def test_phase_c_live_route_html_complete_bytes_equal_frozen_original(self):
        before = guard.scan_source(RUN_MAP_HTML)[0]
        candidates = [t for t in guard.scan_source(self.source(self.platform + "RunMapWeb.kt"))
                      if t.kind == "raw_string" and t.source.startswith('"""<!doctype html>')]
        self.assertEqual(len(candidates), 1)
        after = candidates[0]
        label = self.catalog_label("ROUTE_MAP_ACCESSIBILITY_LABEL")
        self.assertEqual([h.expression_source for h in before.holes], ["csp", "data", "documentId", "sdkTag"])
        self.assertEqual([h.expression_source for h in after.holes],
                         ["csp", "AppText.Run.ROUTE_MAP_ACCESSIBILITY_LABEL", "data", "documentId", "sdkTag"])
        for values in ({"csp": "", "data": "", "documentId": "", "sdkTag": ""},
                       {"csp": "default-src 'none'; script-src 'nonce-fixture';",
                        "data": '{"synthetic":"<>&\\u2028"}', "documentId": "fixture_doc",
                        "sdkTag": '"synthetic_sdk_tag"'}):
            with self.subTest(values=values):
                self.assertEqual(self.assemble(before, values), self.assemble(
                    after, {**values, "AppText.Run.ROUTE_MAP_ACCESSIBILITY_LABEL": label}))

    def test_phase_c_live_bootstrap_const_concatenation_has_original_complete_bytes(self):
        source = self.source(self.platform + "RunMapBootstrapPolicy.kt")
        match = re.search(r"^    const val emptyDocument = ([^\r\n]+)$", source, re.M)
        self.assertIsNotNone(match)
        initializer = match.group(1)
        tokens = guard.scan_source(initializer)
        self.assertEqual(len(tokens), 2)
        self.assertTrue(all(t.kind == "string" and not t.holes for t in tokens))
        self.assertEqual(initializer[tokens[0].end:tokens[1].start],
                         " + AppText.Run.MAP_TEMPORARILY_UNAVAILABLE_DOCUMENT_TITLE + ")
        actual = (tokens[0].decoded_literal + self.catalog_label("MAP_TEMPORARILY_UNAVAILABLE_DOCUMENT_TITLE")
                  + tokens[1].decoded_literal).encode("utf-8")
        expected = guard.scan_source(EMPTY_MAP_DOCUMENT)[0].decoded_literal.encode("utf-8")
        self.assertEqual(len(expected), 139)
        self.assertEqual(hashlib.sha256(expected).hexdigest(),
                         "af4e5d3d3a20675a68612da77faef4f9188e4707e08f900830fdc5c7c498637f")
        self.assertEqual(actual, expected)

    def test_phase_c_repository_has_no_remaining_program_deferral(self):
        entries = guard.load_allowlist(self.root / "scripts/display-text-exceptions.json")
        self.assertFalse(any(e["category"] == "deferred_program" for e in entries))
        result = guard.run_guard(self.root)
        self.assertTrue(result.ok, result.to_dict())
        self.assertEqual(result.deferred_tokens, 0)


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
