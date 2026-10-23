#!/usr/bin/env python3
"""Offline source/plist guards, NOT a Kotlin/Native or device test. No dependency or network access."""
from pathlib import Path
import plistlib
import re

ROOT = Path(__file__).resolve().parents[2]
IOS = ROOT / "shared/src/iosMain/kotlin/io/github/betterzslf/platform"
files = {name: (IOS / name).read_text(encoding="utf-8") for name in (
    "IosCapabilityRules.kt", "FilePicker.ios.kt", "LocationPermission.ios.kt",
    "LocationTracker.ios.kt", "BluetoothAccess.ios.kt",
)}
passed = 0


def check(name, condition):
    global passed
    if not condition:
        raise AssertionError(name)
    passed += 1
    print(f"PASS source guard: {name}")


picker = files["FilePicker.ios.kt"]
permission = files["LocationPermission.ios.kt"]
tracker = files["LocationTracker.ios.kt"]
bluetooth = files["BluetoothAccess.ios.kt"]
rules = files["IosCapabilityRules.kt"]
check("all platform actuals present", all(signature in "\n".join(files.values()) for signature in (
    "actual fun rememberFilePicker", "actual fun rememberLocationPermissionRequester",
    "actual fun createLocationTracker", "actual fun rememberBluetoothAccess", "actual fun createWaterEpilogueContext",
)))
check("explicit document picker and single selection", "UIDocumentPickerViewController(" in picker and "allowsMultipleSelection = false" in picker)
check("remember does not present native picker", "presentViewController" not in picker.split("private class IosFilePicker", 1)[0])
check("cancel and interactive dismiss callbacks handled", "documentPickerWasCancelled" in picker and "presentationControllerDidDismiss" in picker)
check("scoped access paired with finally", "startAccessingSecurityScopedResource()" in picker and "finally {\n        if (hasScope) url.stopAccessingSecurityScopedResource()" in picker)
check("external documents coordinate reading", "coordinateReadingItemAtURL" in picker and "NSFileCoordinatorReadingWithoutChanges" in picker)
check("metadata limit checked before stream creation", picker.index("knownSize > MAX_PICKED_FILE_BYTES") < picker.index("NSInputStream.inputStreamWithURL"))
check("unknown sizes still use bounded streaming", "readBoundedPickedBytes(" in picker and "input.read(" in picker and "ByteArray(limit + 1)" in rules)
check("no whole NSData or read-to-end API", not re.search(r"NSData|dataWithContentsOf|readDataToEndOfFile|readToEnd", picker))
check("read jobs cancelled and native UI delegates released", "readJob?.cancel()" in picker and "current.delegate = null" in picker and "current.presentationController?.delegate = null" in picker)
check("pending provider coordination explicitly cancelled", "coordinator?.cancel()" in picker and "coordinator = null" in picker)
check("file result exposes basename only", "PickedFile(url.lastPathComponent" in picker and not re.search(r"\.path\b|absoluteString|localizedDescription", picker))
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
check("Bluetooth remember does not instantiate central", "CBCentralManager(" not in bluetooth.split("private class IosBluetoothAccess", 1)[0])
request = bluetooth.split("override fun requestPermissions()", 1)[1].split("override fun requestEnable()", 1)[0]
check("central creation only in permission action", bluetooth.count("CBCentralManager(") == 1 and "CBCentralManager(" in request)
check("Bluetooth manager never scans or connects", not re.search(r"\.scanForPeripherals|\.connectPeripheral|\.retrievePeripheral", bluetooth))
check("Bluetooth native delegate is cleaned up", "manager?.delegate = null" in bluetooth and "manager = null" in bluetooth)
check("epilogue Gregorian Shanghai and seed range", "NSCalendarIdentifierGregorian" in bluetooth and 'timeZoneWithName("Asia/Shanghai")' in bluetooth and "Random.nextInt(1, 10_000)" in bluetooth and "currentCalendar" not in bluetooth)
check("native errors and PII are not logged", not re.search(r"println\(|NSLog\(|localizedDescription|allHTTPHeaderFields", "\n".join(files.values())))
info = plistlib.loads((ROOT / "iosApp/Info.plist").read_bytes())
check("background location mode declared", info.get("UIBackgroundModes") == ["location"])
check("precise foreground purpose declared", bool(info.get("NSLocationWhenInUseUsageDescription")) and info.get("NSLocationDefaultAccuracyReduced") is False)
check("Bluetooth usage purpose declared", bool(info.get("NSBluetoothAlwaysUsageDescription")))
check("source line endings and whitespace", all(text.endswith("\n") and all(line == line.rstrip() for line in text.splitlines()) for text in files.values()))
print(f"All {passed} source/plist guards passed; UIKit/Core Location/Core Bluetooth runtime behavior remains untested.")
