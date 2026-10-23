# Shared edition source contract

## Selection and public contract

`betterZslfEdition` is the single Gradle property for every target. Its exact legal values are `standard` and `pro`; the default is `standard`. Other values fail configuration. `commonMain` adds only `src/<edition>Main/kotlin`; `commonTest` similarly adds `src/<edition>Test/kotlin`. This is compiler-input selection, not a runtime-only button flag.

Both selected source directories provide `io.github.betterzslf.edition.BuildEdition`:

| Public constant | standard | pro |
| --- | --- | --- |
| `id` | `standard` | `pro` |
| `isPro` | `false` | `true` |
| `updateRepository` | `better-zslf/better-zslf` | `better-zslf/pro-ci` |
| `androidApplicationId` | `io.github.betterzslf` | `io.github.betterzslf.pro` |
| `versionNameSuffix` | empty | `-pro` |

`features: EditionFeatureFlags` is immutable. The common flags default to both `false`; Pro explicitly selects both `true`. Neither server data nor saved settings can turn standard into Pro. `EditionAccess` checks build identity AND the corresponding flag for every Pro entry and dispatcher. `pushUnique`, restored logged-out routes, `FeaturePage`, `RunFeaturePage`, and direct Pro page/facade dispatch all fail closed.

`EditionFeatures` is the common application boundary (`HomeEntry`, `LoginEntry`, `Page`). Only the selected factory implements it. `AppGraph` owns a single lazy facade, not duplicate app graphs. No common graph/UI contract exposes a water protocol/controller type.

## Actual standard exclusions

`shared/build.gradle.kts` applies these exclusions to all source sets when standard is selected; the original source stays in the repository:

- `feature/water/**`: all key tables, algorithms, wire protocol, session machine, failure guard, BLE controller and Kable transport.
- `feature/waterui/**` and `ui/water/**`: presenter and entire heater UI.
- `platform/BluetoothAccess*.kt`: the common expect declarations and Android/iOS/JVM BLE actual implementations, including the epilogue context. Kable is also absent from standard dependencies.
- `feature/run/RunRouteParser.kt`, `RunRouteValidation.kt`, `RunDetourPlanner.kt`: GPX/KML parser, local route validation/normalization, supplemental-distance and planning engines.
- `feature/runui/RunImportController.kt`, `RunManualDraft.kt`: in-memory import/preview and hand-drawn route models.
- `ui/run/RunImportPage.kt`, `RunManualDraftSession.kt`, `RunManualDraftSheet.kt`: import/planning page and manual editor lifecycle/sheet.
- Unselected `proMain` is never added: Pro facade, `TrackGenerationOptions` / `RunTrackGenerator.generate` local-preview extension and its random walk helpers are absent from standard. The latter is the existing algorithm relocated without adding any new trajectory generation behavior.
- The selected standard `RunMapEditing` has no draw JavaScript, click bindings, or draw-event parser. It forces `editMode=false`; its Pro counterpart contains the existing editor bridge. Native GPS, checkpoint selection and map rendering remain common.

## Shared real-running functionality retained

- Tasks, scores, history, server track decoding and recommended/server map paths.
- Ordinary `RunRuntime`, `RunSessionCoordinator`, native location trackers/permission requests, original sample display and local storage/recovery.
- `RunGeometry.kt`, `RunWireCoordinates.kt`, the canonical track model, `RunTrackValidator`, and `RunTrackGenerator.fromSamples` with its original filtering and 100,000-point bound (`RunTrackLimits` breaks the former import-parser dependency).
- Explicit start/capture/finish/upload confirmation, owner/token generation fences, final publish checks, queue holds and account change handling.
- Production `AppGraph` explicitly passes `transportFactory = null` in BOTH editions. No new production writes, silent confirmations, GPS substitution, or runtime permission shortcuts were added.
- `RunDisplay` was extracted from the Pro import-controller file into common source so real sessions retain formatting.
- File pickers are still common because venue booking uses attachment selection; they are not exclusive to route import.
- Profile/session/avatar lifecycle and account-recovery behavior were not changed.

## Updates and native integration

`GithubReleasePolicy` builds API, release and exact asset URLs from the selected repository. `StableVersion` keeps numeric, overflow-safe comparison. Standard rejects `-pro`; Pro accepts numeric marketing versions and its exact `-pro` suffix, but rejects arbitrary prerelease/build metadata. Exact tag/name/URL/SHA/size checks remain. Android policy delegates to the same common install policy rather than drifting copies; explicit download and archive verification also require `context.packageName == BuildEdition.androidApplicationId`, with archive package/version/signature/newer-code checks retained.

Android application ID must match the selected constant; namespace stays unchanged. Pro BLE permissions belong only to the Pro manifest overlay; standard keeps precise/foreground location required by real GPS. Android versionName/tag/APK filename should agree, e.g. `1.2.3-pro`, `v1.2.3-pro`, `better-zslf-1.2.3-pro.apk` for Pro. Version-code coordination is owned by Lead.

iOS must pass the same property into framework compilation and use an edition-specific bundle ID (standard bundle and its Pro counterpart), so the existing bundle-derived keychain service remains isolated. Marketing/CFBundleShortVersionString stays numeric. Shared `PlatformServices.ios.kt` appends only the selected display suffix to its read value. Standard has no Bluetooth implementation; plist BLE usage copy belongs only to Pro. `IosStartupDiagnostics.kt` was not edited; its existing `ae2e29ad215c2637f8b7f936fbb5292d496fc5da60a8edc8664be38b94d3217e` pin remains the required external verification.

## Test selection and handoff verification

Standard excludes only Pro implementation tests: `feature/water/**`, `feature/waterui/**`, `RunGeometryAndImportTest`, `RunRouteAndTrackTest`, `RunImportControllerTest`, `RunManualDraftTest`, `RunManualImportControllerTest`, and `RunManualDraftSessionTest`. Before exclusion, mixed-file real geometry (five tests), real canonical sample conversion (one test), and display formatting (three tests) were extracted into common test files. Their assertions remain in both editions. The water const alias assertion was extracted from the common golden test into `proTest`; all other AppText golden assertions stay common. Existing AppText strings were not rewritten; new edition copy is in `AppText.Edition` with independent golden expectations.

New regressions cover default-closed switches, standard rejection even with true Pro flags, preserved real-run routes, navigation/offline restoration, selected constants, other-repository rejection, suffix rules, inert facade construction, and maps without the standard draw bridge. `jvmTest/edition/EditionCompilationTest` checks ACTUAL output classes: Pro protocol/controllers/editors/preview generator must be absent in standard and present in Pro, while real GPS/track/map/geometry classes stay in both.

No Gradle, build, ADB, Git, HTTP, or test execution was performed by this worker. Read/glob/grep/edit/write review is not a test-pass claim. Lead must run both editions' compilation/tests, compare full Pro baseline (745 existing tests plus regressions), inspect standard artifacts and manifest, and verify the iOS pin. Switching editions should use clean or otherwise isolated outputs so stale classes cannot mask exclusion errors.

Lead-owned source guard/script follow-ups:

- Add `commonMain/.../edition/`, `proMain/kotlin/`, and `standardMain/kotlin/` to display-literal scan scope; tests remain test scope.
- Update source-level updater tests that formerly extracted duplicate Android-local regex/URL literals to follow the shared install policy and selected repo/suffix/app-ID fence.
- Review/rebase source-bound literal allowlist positions affected by facade extraction; do not change the iOS startup diagnostic pin.
- Keep existing full Pro test coverage, and separately report the smaller standard count caused by true exclusive-test filtering.
