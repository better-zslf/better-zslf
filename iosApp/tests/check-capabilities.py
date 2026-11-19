#!/usr/bin/env python3
"""Offline public GPS/privacy/plist guards, NOT a Kotlin/Native or device test.

Only public sources are read. Pro Bluetooth/picker guards belong to the private product.
No dependency resolution, signing, sensor operation, or network access is performed.
"""
from pathlib import Path
import plistlib
import re

ROOT = Path(__file__).resolve().parents[2]
IOS = ROOT / "shared/src/iosMain/kotlin/io/github/betterzslf/platform"
files = {name: (IOS / name).read_text(encoding="utf-8") for name in (
    "IosCapabilityRules.kt", "LocationPermission.ios.kt", "LocationTracker.ios.kt",
    "PlatformServices.ios.kt", "KeychainKeyValueStore.kt", "HtmlPanel.ios.kt", "ScheduleNative.ios.kt",
)}
files["UnsupportedNativeScheduleOwner.kt"] = (ROOT / "platform/schedule-native/src/commonMain/kotlin/io/github/betterzslf/platform/UnsupportedNativeScheduleOwner.kt").read_text(encoding="utf-8")
passed = 0


def check(name, condition):
    global passed
    if not condition:
        raise AssertionError(name)
    passed += 1
    print(f"PASS source guard: {name}")


permission = files["LocationPermission.ios.kt"]
tracker = files["LocationTracker.ios.kt"]
rules = files["IosCapabilityRules.kt"]
services = files["PlatformServices.ios.kt"]
storage = files["KeychainKeyValueStore.kt"]
article = files["HtmlPanel.ios.kt"]
for filename, owner, capability in (
    ("LocationPermission.ios.kt", "IosLocationPermissionLauncher", "UserRequestLauncher"),
    ("LocationTracker.ios.kt", "IosLocationTracker", "NativeLocationTracker"),
):
    header = files[filename].split(f"private class {owner}", 1)[1].split("{", 1)[0]
    check(f"{owner} separates native delegate and clears callbacks", capability in header and "NSObject" not in header and "DelegateProtocol" not in header and "nativeDelegate.clear()" in files[filename])
check("public GPS actuals present", "actual fun rememberLocationPermissionRequester" in permission and "actual fun createLocationTracker" in tracker)
check("permission requested only by explicit launcher", permission.count("requestWhenInUseAuthorization()") == 1 and "requestWhenInUseAuthorization" not in permission.split("private class IosLocationPermissionLauncher", 1)[0])
check("precise-only permission", "CLAccuracyAuthorizationFullAccuracy" in permission and "hasPreciseLocationPermission" in tracker)
check("tracker does not request or upgrade permission", not re.search(r"requestWhenInUseAuthorization|requestAlwaysAuthorization|requestTemporaryFullAccuracyAuthorization", tracker))
check("tracker is process singleton with no replay", "processLocationTracker" in tracker and "MutableSharedFlow<RunLocationSample>(replay = 0)" in tracker)
check("manager constructed only after explicit start", tracker.count("CLLocationManager()") == 1 and tracker.index("override fun start(") < tracker.index("CLLocationManager()"))
check("best accuracy and fitness configuration", "kCLLocationAccuracyBestForNavigation" in tracker and "CLActivityTypeFitness" in tracker and "pausesLocationUpdatesAutomatically = false" in tracker)
check("foreground start and background indicators", "UIApplicationStateActive" in tracker and "allowsBackgroundLocationUpdates = true" in tracker and "showsBackgroundLocationIndicator = true" in tracker)
check("native 100m and 10..60s gate", "accuracyMeters !in 0.0..100.0" in rules and "samplingSeconds.coerceIn(10, 60)" in rules and "receiptUptimeMillis - previousReceipt < intervalMillis" in rules)
check("cached callbacks guarded by manager and session generation", "manager != this.manager" in tracker and "currentGeneration != generation" in tracker and "timestampMillis < startedAtMillis" in rules)
check("stop cancels emissions and native manager", "emissionJob?.cancel()" in tracker and "it.stopUpdatingLocation()" in tracker and "it.delegate = null" in tracker and "manager = null" in tracker)
check("terminal close cancels scope", "scope.cancel()" in tracker and "closed = true" in tracker)
check("public iOS sources have no Bluetooth or file-picker actual", all(not (IOS / name).exists() for name in ("BluetoothAccess.ios.kt", "FilePicker.ios.kt")) and not re.search(r"rememberBluetoothAccess|rememberFilePicker|CoreBluetooth|readBoundedPickedBytes", "\n".join(files.values())))
check("Keychain service stays bundle isolated", "NSBundle.mainBundle.bundleIdentifier" in services and "kSecAttrService to retainedService" in storage and "kSecAttrSynchronizable to kCFBooleanFalse" in storage and "kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly" in storage)
check("HTTP shared cookies credentials and cache disabled", all(value in services for value in ("HTTPShouldSetCookies = false", "HTTPCookieStorage = null", "URLCredentialStorage = null", "URLCache = null", "NSURLRequestReloadIgnoringLocalCacheData")))
check("article renderer stays nonpersistent and script free", "WKWebsiteDataStore.nonPersistentDataStore()" in article and "allowsContentJavaScript = false" in article and "preferences.javaScriptEnabled = false" in article)
check("schedule bridge stays public and explicitly unsupported on iOS", "actual fun createScheduleNativeBridge(): ScheduleNativeBridge = newUnsupportedScheduleNativeBridge()" in files["ScheduleNative.ios.kt"] and "fun newUnsupportedScheduleNativeBridge(): ScheduleNativeBridge = UnsupportedScheduleNativeBridge()" in files["UnsupportedNativeScheduleOwner.kt"] and "internal class UnsupportedScheduleNativeBridge" in files["UnsupportedNativeScheduleOwner.kt"] and "override val supported: Boolean = false" in files["UnsupportedNativeScheduleOwner.kt"] and "MutableStateFlow<ScheduleNativeOpenRequest?>(null)" in files["UnsupportedNativeScheduleOwner.kt"])
check("native errors and PII are not logged", not re.search(r"println\(|NSLog\(|localizedDescription|allHTTPHeaderFields", "\n".join(files.values())))
info = plistlib.loads((ROOT / "iosApp/Info.plist").read_bytes())
check("background location mode declared without Bluetooth background mode", info.get("UIBackgroundModes") == ["location"])
check("precise foreground purpose preserved", info.get("NSLocationWhenInUseUsageDescription") == "仅在你明确开始校园跑后记录精确路线，后台连续记录会显示系统定位指示；暂停或结束即停止。" and info.get("NSLocationDefaultAccuracyReduced") is False)
check("Standard has no Bluetooth purpose declaration", not any(key.startswith("NSBluetooth") for key in info))
check("public bundle and numeric Apple version bridges retained", info.get("CFBundleIdentifier") == "$(PRODUCT_BUNDLE_IDENTIFIER)" and info.get("CFBundleShortVersionString") == "$(MARKETING_VERSION)" and info.get("BetterZslfDisplayVersion") == "$(BETTER_ZSLF_DISPLAY_VERSION)")
check("source line endings and whitespace", all(text.endswith("\n") and all(line == line.rstrip() for line in text.splitlines()) for text in files.values()))
print(f"All {passed} public source/plist guards passed; UIKit/Core Location runtime behavior remains untested.")
