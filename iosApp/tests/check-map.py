#!/usr/bin/env python3
"""Offline iOS source guards; optional --node NODE runs the real file-input guard in DOM mocks.

No Kotlin/Native build, WKWebView, location, map SDK/CDN request, or device interaction is performed.
These checks prove neither an SDK-26 build nor iOS-14 runtime/permission behavior.
"""
from pathlib import Path
import argparse
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "shared/src/iosMain/kotlin/io/github/betterzslf/platform/RunMapPanel.ios.kt"
text = SOURCE.read_text(encoding="utf-8")
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--node", help="Existing Node executable; no packages are downloaded.")
args = parser.parse_args()
passed = 0


def check(name, condition):
    global passed
    if not condition:
        raise AssertionError(name)
    passed += 1
    print(f"PASS map source guard: {name}", flush=True)


def section(start, end):
    return text.split(start, 1)[1].split(end, 1)[0]


owner_header = section("private class IosRunMapRenderer", "{")
delegate_header = section("private class RunMapNativeDelegate", "{")
message = section("private fun onMessage(", "private fun onFinished(")
release = section("fun release(", "fun dispose(")
load = section("private fun loadDocument(", "private fun applyLatestModel(")
check("actual and independent native view", "actual fun RunMapPanel(model: RunMapModel" in text and "UIKitView(" in text)
check("latest callback and model are used", "rememberUpdatedState(model)" in text and "rememberUpdatedState(onEvent)" in text and "currentModel?.invoke()" in message)
check("pure Kotlin owner", "NSObject" not in owner_header and "Protocol" not in owner_header)
check("native delegate has only Objective-C supertypes", ") : NSObject(), WKNavigationDelegateProtocol, WKScriptMessageHandlerProtocol, WKUIDelegateProtocol" in delegate_header)
check("composition and view release both clean up", "DisposableEffect(renderer)" in text and "onRelease = { renderer.release(it) }" in text)
check("fresh ephemeral store and process pool", "WKWebsiteDataStore.nonPersistentDataStore()" in text and "processPool = WKProcessPool()" in text)
check("fresh handler controller", "val controller = WKUserContentController()" in text and "addScriptMessageHandler(delegate, name = MAP_MESSAGE_HANDLER)" in text)
check("fixed isolated bridge name", 'MAP_MESSAGE_HANDLER = "runMap"' in text)
check("public base constant is used", "val base = RunMapWeb.baseUrl" in load and "RunMapWeb.baseUrl(" not in text)
check("fresh document epoch precedes cancellation", load.index("NSUUID().UUIDString") < load.index("documentId = id") < load.index("view.stopLoading()"))
check("document builder receives fresh ID", "RunMapWeb.document(model, id)" in load)
check("configuration changes trigger reload", "model.provider != loadedProvider || model.apiKey != loadedKey" in text and "changedConfiguration || !hasMapDocument" in text)
check("model changes use update script", "RunMapWeb.updateScript(model, id)" in text and "view.evaluateJavaScript(script)" in text)
check("eval success does not falsely clear SDK or network failure", "else if (failure != null)" not in text and "failure == RunMapFailure.UNCONFIRMED_COORDINATES || failure == RunMapFailure.TOO_MANY_POINTS" in text)
check("late script completion rejected", "revision != scriptRevision" in text and "!isCurrent(view, id)" in text)
check("main-frame bridge only", "!message.frameInfo.mainFrame" in message)
check("owned view and content controller only", "message.webView !== view" in message and "controller !== contentController" in message)
check("message checks channel and both document URLs", "message.name != MAP_MESSAGE_HANDLER" in message and "message.frameInfo.request.URL?.absoluteString" in message and "view.URL?.absoluteString" in message)
check("about blank and owned-base fragments accepted", 'url.substringBefore(\'#\') == "about:blank"' in text and "url.substringBefore('#') == documentBase" in text and "RunMapWeb.isAllowedNavigation(url.substringBefore('#'))" in text)
check("stale configuration messages rejected", "if (!sameConfiguration(model)) return" in message)
check("bounded string message only", "message.body as? String ?: return" in message and "payload.length > MAX_MAP_MESSAGE_CHARS" in message)
check("common parser receives latest model and exact ID", "RunMapWeb.parseEvent(payload, model, id)" in message)
check("ready gates edit/selection events", "else -> if (ready) onEvent?.invoke(event)" in message)
check("only initial owned main-frame navigation", "!allowingInitialNavigation" in text and "action.targetFrame?.mainFrame == true" in text and "action.sourceFrame.mainFrame" in text and "action.navigationType == WKNavigationTypeOther" in text)
check("navigation always decides allow or cancel", "decisionHandler(if (allow) WKNavigationActionPolicy.WKNavigationActionPolicyAllow else WKNavigationActionPolicy.WKNavigationActionPolicyCancel)" in text)
check("popups return no webview", "windowFeatures: WKWindowFeatures): WKWebView? = null" in text and "javaScriptCanOpenWindowsAutomatically = false" in text)
check("camera microphone and motion native deny", "requestMediaCapturePermissionForOrigin" in text and "requestDeviceOrientationAndMotionPermissionForOrigin" in text and text.count("decisionHandler(WKPermissionDecision.WKPermissionDecisionDeny)") == 2)
check("no newer Objective-C file-panel class strongly linked", "WKOpenPanelParameters" not in text and "runOpenPanelWithParameters" not in text)
check("old iOS file guard runs before content in all frames", "WKUserScriptInjectionTimeAtDocumentStart" in text and "forMainFrameOnly = false" in text and "source = MAP_FILE_INPUT_GUARD" in text)
check("file selection entry points all guarded", all(value in text for value in ("'dispatchEvent'", "'showOpenFilePicker'", "'showDirectoryPicker'", "'showSaveFilePicker'", "descriptor.configurable === false")))
check("native navigation callbacks are epoch guarded", text.count("navigation !== activeNavigation") == 2 and "onFailure?.invoke(webView, didFailProvisionalNavigation)" in text)
check("clashing Objective-C selectors explicitly annotated", text.count("@ObjCSignatureOverride") == 2)
check("native failure closes late event and script gates", text.count("acceptingEvents = false") >= 4 and "!acceptingEvents" in message and "!acceptingEvents || revision != scriptRevision" in text)
check("release invalidates document before native stop", release.index("documentId = null") < release.index("view.stopLoading()"))
check("release removes native delegates and scripts", all(item in release for item in ("view.navigationDelegate = null", "view.UIDelegate = null", "removeScriptMessageHandlerForName", "removeAllUserScripts()", "nativeDelegate?.clear()")))
check("delegate owner closures are cleared", all(item in text for item in ("allowNavigation = null", "onMessage = null", "onFinished = null", "onFailure = null", "onTerminated = null")))
check("dispose forgets model callback and public key", "currentModel = null" in text and "onEvent = null" in text and "loadedKey = null" in release)
check("no school session or sensor operations", not re.search(r"SessionStore|SchoolToken|Authorization|CLLocationManager|CoreBluetooth|RunRuntime|createLocationTracker|startUpdatingLocation", text))
check("no raw values or NSError text logging", not re.search(r"println\(|NSLog\(|localizedDescription|allHTTPHeaderFields|console\.log", text))
check("only owned bundled HTML loads", "view.loadHTMLString(" in text and not re.search(r"loadRequest\(|openExternalHttpsUrl|openURL\(|evaluateJavaScript\(\s*\"", text))
check("no unsupported iOS 27 geolocation selector", "requestGeolocationPermissionForOrigin" not in text)
check("clean source whitespace", text.endswith("\n") and all(line == line.rstrip() for line in text.splitlines()))

if args.node:
    match = re.search(r'private val MAP_FILE_INPUT_GUARD = """(.*?)"""\.trimIndent\(\)', text, re.S)
    check("real file guard extracted", match is not None)
    js = """
const vm = require('node:vm');
const assert = require('node:assert/strict');
const guard = GUARD;
function fixture(modern) {
  const context = vm.createContext({modern});
  vm.runInContext(`
    window = globalThis; clicks = 0; picks = 0; dispatches = 0; globalPicks = 0; listeners = [];
    EventTarget = class { dispatchEvent(event) { dispatches++; return true; } };
    HTMLElement = class extends EventTarget { constructor(tag) { super(); this.tagName = tag; } click() { clicks++; } };
    HTMLInputElement = class extends HTMLElement { constructor(type) { super('INPUT'); this.type = type; } };
    if (modern) HTMLInputElement.prototype.showPicker = function() { picks++; };
    if (modern) ['showOpenFilePicker', 'showDirectoryPicker', 'showSaveFilePicker'].forEach(function(name) {
      window[name] = function() { globalPicks++; return Promise.resolve([]); };
    });
    document = {addEventListener: function(name, handler, capture) { listeners.push({name, handler, capture}); }};
  `, context);
  vm.runInContext(guard, context);
  return context;
}
let tests = 0;
function test(name, fn) { fn(); tests++; console.log('PASS DOM-mock guard: ' + name); }
const c = fixture(true);
test('file click and base-prototype bypass blocked', () => {
  vm.runInContext(`file = new HTMLInputElement('file'); file.click(); HTMLElement.prototype.click.call(file);`, c);
  assert.equal(c.clicks, 0);
});
test('non-file controls keep native click behavior', () => {
  vm.runInContext(`normal = new HTMLInputElement('text'); normal.click(); new HTMLElement('DIV').click();`, c);
  assert.equal(c.clicks, 2);
});
test('file showPicker blocked but non-file picker preserved', () => {
  vm.runInContext(`file.showPicker(); normal.showPicker();`, c);
  assert.equal(c.picks, 1);
});
test('user click capture blocks file only', () => {
  assert.equal(c.listeners.length, 1); assert.equal(c.listeners[0].capture, true);
  let prevented = 0, stopped = 0;
  c.listeners[0].handler({target: {closest: () => ({})}, preventDefault(){prevented++}, stopImmediatePropagation(){stopped++}});
  assert.equal(prevented, 1); assert.equal(stopped, 1);
  c.listeners[0].handler({target: {closest: () => null}, preventDefault(){prevented++}, stopImmediatePropagation(){stopped++}});
  assert.equal(prevented, 1); assert.equal(stopped, 1);
});
test('SDK cannot simply replace locked guards', () => {
  for (const [p, name] of [[c.HTMLElement.prototype, 'click'], [c.HTMLInputElement.prototype, 'showPicker'], [c.EventTarget.prototype, 'dispatchEvent']]) {
    const descriptor = Object.getOwnPropertyDescriptor(p, name);
    assert.equal(descriptor.configurable, false); assert.equal(descriptor.writable, false);
    assert.throws(() => Object.defineProperty(p, name, {value(){}}));
  }
});
test('legacy environment without showPicker still blocks file click', () => {
  const old = fixture(false);
  vm.runInContext(`new HTMLInputElement('file').click(); new HTMLInputElement('text').click();`, old);
  assert.equal(old.clicks, 1); assert.equal(old.picks, 0);
});
test('detached synthetic file activation blocked', () => {
  vm.runInContext(`file.dispatchEvent({type:'click'}); EventTarget.prototype.dispatchEvent.call(file, {type:'click'}); normal.dispatchEvent({type:'click'}); file.dispatchEvent({type:'change'});`, c);
  assert.equal(c.dispatches, 2);
});
test('repeated hardening does not overwrite or throw', () => {
  const click = c.HTMLElement.prototype.click;
  vm.runInContext(guard, c);
  assert.equal(c.HTMLElement.prototype.click, click);
});
Promise.allSettled(['showOpenFilePicker', 'showDirectoryPicker', 'showSaveFilePicker'].map(name => c[name]())).then(results => {
  test('global file-picker APIs deny without native calls', () => {
    assert.equal(c.globalPicks, 0);
    for (const result of results) {
      assert.equal(result.status, 'rejected'); assert.equal(result.reason.name, 'NotAllowedError');
    }
  });
  console.log(`All ${tests} DOM-mock tests passed; this is not WebKit/native runtime validation.`);
}).catch(error => { console.error(error); process.exitCode = 1; });
""".replace("GUARD", json.dumps(match.group(1), ensure_ascii=True), 1)
    subprocess.run([args.node, "-e", js], cwd=ROOT, check=True)
print(f"All {passed} iOS map source guards passed; Native build and real map/permission behavior remain for CI/device verification.")
