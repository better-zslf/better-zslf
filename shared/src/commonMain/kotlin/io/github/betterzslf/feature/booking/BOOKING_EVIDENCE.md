# Booking / fitness implementation handoff

## Public integration contract

`io.github.betterzslf.ui.booking.BookingPage(graph: AppGraph, page: Route.Page, onOpen: (Route) -> Unit, onBack: () -> Unit)` handles:

- `venues`, `venue` (plain `siteId`), `venuetimes` (plain id or encoded `BookingSelection`), `venueapply` (encoded selection including `dateTime` and time ids).
- `myvenues`, `venueapplication` (`applyId`).
- `fitness`, `fitnessitems` (plain `physicalId` or encoded selection), `fitnessenrol` (encoded selection).
- `BookingSelection` JSON stores identifiers, date and local project selection only; no session, card number, form contents or file bytes.

Lead owns dispatcher/Gradle/platform integration. This task only changes `ui/booking`, `feature/booking` and the matching common tests.

## Evidence and limitations

Docs/13 §§5.5/5.8 are the live read/empty-input baseline. Successful venue/fitness writes were **not tested against production** in this implementation. Static evidence does not establish server acceptance. The API-spec's write `$ref e` is not itself a field schema.

After Lead requested exact shipped-client recovery, the reverse-engineer-anything skill was loaded. Reused api-core's complete CLI REA Evidence instead of re-running the analysis:

- Parent Evidence: `ev_12485294a1c7e75f791ec2e1e0e0719af08a30143b3e3fe04c18110e56212f6b`.
- This agent's `inspect-analysis-view` summary: `ev_8709b8ab2aa3f30c18bd9d9adfa24853626fb6c8391d40f1dba6b62c0b1be04a`.
- Artifact root: `work/apk/assets/apps/__UNI__A8FC317/www`; selected `app-service.js` SHA256: `887642f9f021c865f838c731378980e9c9b426405bc15c754e1cc4dcdd9a712f`.
- REA selected only this source; 19 other files were inventoried but not analyzed. Application/semantic graph coverage is partial and the semantic unknown ceiling was reached. No bundle bootstrap, application runtime or network was executed.
- Follow-up slices were independently SHA-checked inert text reads. The offsets below are **UTF-16 string indices**, not byte offsets. These bounded manual slices complement REA's artifact identity; they are not claimed as separate REA runtime observations.

### Venue

- Endpoint aliases at UTF16 340850–341604: `qu/addApply`, `Gu/addSiteApplyRelated`, `Hu/addApplySelectTime`, `Wu/addApplyDenfition`, `Yu/intProcess`, `Qu/afterUploadFile`.
- Form setup/caller UTF16 361800–363500: `id,name,userId,uerName,teacherId,teacher,applicants,objectId,place,time,deviceName,remark,venueStatus,status`, dynamic form inputs `content,uName,uPhone`. `uerName` spelling is deliberate. New creation sets `status=0`. Object/category default is the exact shipped constant, not a user identity.
- Create @362577 reads `data.sportsVenueApply.id`; association caller @363056 sends `{venueId,applyId}`; @363166 sends `{venueTimeId:<CSV ids>,applyId}`; @363286 sends `{userId,teacherId,teacherName,applyId}`. The original runs associations with Promise.all; our client runs sequentially and retains completed steps/applyId before stopping on failure.
- `venueApp` construction @393788 gets `venueStatus` from `getSiteInfo.data.status`, venue name/address from the same object. No status value is invented when info is empty.
- Time query @392183 sends `{venueId,dateTime,applyId:""}`. Response `placeTimeList` is the candidate list; `isUse===1` disables a slot; matching ids in `applyTimeList` are also disabled. Typed repository query retains compatibility `siteId` too, implemented by api-core.
- `intProcess` caller @364615 reads approval timeline `nodeName,approver,opinion,reviewTime`. **This is not a cancellation endpoint.** UI displays the timeline and leaves venue cancellation explicitly unavailable. It never calls intProcess as a write.
- My applications @3418xx uses `{userId,status:"",offset,limit:10}`. Shared paged model uses these observed fields, merges records, preserves failed page data and detects an ignored cursor.

### Attachments

- Action assignment @362176: `/run/site/addApplyFile`.
- `build-form-data` around @364960 resolves `{applyId,userId}`; wd-upload default `name=file`. Upload view @367400–370700 exists only for `sportsVenueApply.status===1`, accepts stamped-document photos, limit=9, auto-upload=false.
- Each file success increments a counter; once all files complete, @365169 calls `afterUploadFile(applyId)` and reloads application details. Existing file entries are read as `fileList[].path/id`.
- The original uploader checks HTTP statusCode (default 200); it does not read upload body/data or file ids. Successful upload body schema is unknown. api-core added the required 56th path with token and file/applyId/userId only: empty successful body accepted; explicit business errors honored; unknown nonempty body becomes Decode/uncertain. Feedback defaults `feedbackImage/sch_feedback/tableId` are never reused.
- Our workflow uploads serially, stops on failure, calls afterUpload only after successful transfers, then compares the refreshed file receipt set. Missing new receipts => UNCERTAIN; no silent retry/reupload.
- Common picker retains <=8 MiB per file in memory, limits UI to 9 photos, sanitizes filenames, and is invoked only by an explicit button. No raw paths, picker error text, file content or form data are logged/persisted by this module.

### Fitness

- Test-list caller @4833xx: `{userId,offset,limit:10}` and `physicalTestList`; a route id is resolved across real pages rather than arbitrarily stopping after 50/100 results.
- Projects @535144: `getPhysicalRelatedItemsList(physicalId)` reads `getRelatedItemsList`. **Projects are display/local-check choices; the original enrol payload does not include item ids.** No `itemIds` request field is invented.
- Enrol @537300–538700 / call @538473 exact fields: `physicalId,userId,idcard,name,studentID,status:1,sex:"男"|"女",physicalTime:"YYYY-MM-DD HH:mm",period:"上午"|"下午",physicalUserId:rId`. Original date selector formats date-only at `00:00`; this is kept.
- Cancel @539084: `{id:rId,cancelText,status:4}`. Requires a fresh existing rId and explicit final confirmation. Cancel reason is retained only in memory.
- Capacity fields are observed directly around @535824: male morning `mlbCount/mLimitBoy`, male afternoon `albCount/aLimitBoy`, female morning `mlgCount/mLimitGirls`, female afternoon `algCount/aLimitGirls`. Date range `testStart/testEnd`, weekdays `showDay` (Sunday=0). Missing metadata pauses preview rather than inventing limits/dates.
- Original cancellation timing @536500–537200: userStatus 1, date+08:00 morning or +12:00 afternoon, floor(hours remaining)>5. Cancellation-count restriction uses `pcount>mcount` (strict >, as shipped). Common arithmetic uses school UTC+08:00 instead of device-local Date parsing.

## Operation invariants

1. No action runs from entering a route, creating a controller, selecting a file, selecting projects, generating a preview or dismissing a dialog.
2. Immutable preview -> separate final dialog -> checkbox -> confirm. Stale preview ids, missing final acknowledgement and duplicate taps do not start operations.
3. The workflow mutex and terminal state prevent duplicate submission. A failed/uncertain/completed attempt is not reset into a retry; the user gets refresh/detail navigation and retained completed steps/receipt.
4. Read queries keep prior successful content on refresh failures. Full project results use actual LazyColumn items plus local search/windowing; no 50-item hard cutoff or 1489-child Column.
5. Private identity/contact values stay out of routes/debug strings, sensitive display fields are masked, credentials are excluded from generic details. Card/phone/file bytes are memory-only.

## Offline validation handoff

`BookingDataTest`, `BookingConfirmationTest`, `BookingWorkflowContractTest` cover calendar/capacity boundaries, 1489 records, route payloads, raw vs proven slot states, blocked venue cancellation, stale/final acknowledgement/double tap, partial failures/cancellation, real repository MockEngine field/path contracts, exact fitness mapping, empty-id guard, serial attachment/afterUpload/read-back, missing receipts, and HTTP200 business failure.

Only Lead runs project Gradle. This teammate did not run Gradle, production writes, real bookings/enrolments, or real uploads. Android native picker/iOS native builds still require Lead/native-CI validation. No commit/push was performed by this teammate.
