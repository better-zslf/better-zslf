# Content protocol evidence and design boundaries

## Provenance

- REA `analyze_javascript_application`, selected source `app-service.js`, static analysis only; no JavaScript execution or service requests.
- Full CLI Evidence is retained in private development records, not distributed with this repository. Evidence ID: `ev_12485294a1c7e75f791ec2e1e0e0719af08a30143b3e3fe04c18110e56212f6b`.
- Selected upstream JavaScript bundle SHA-256: `887642f9f021c865f838c731378980e9c9b426405bc15c754e1cc4dcdd9a712f`.
- Other 19 inventoried files were not semantically selected. AST graph coverage is partial; unknown/dynamic behavior is not absence. Offsets below are UTF-16 string indexes, not byte offsets. Runtime shape conclusions additionally use private live API test records; those inputs are not published.

## Observed content call sites

| UTF-16 index | Exact observed behavior |
|---|---|
| 583704 | `ji({offset:i.value,limit:"10",typeId:0})`; system messages use `data.noticeList`. This corrects the old `type` query alias. |
| 584196 / 584227 | `0===o.value ? await Gi(e.id) : 1===o.value && await qi(e.id)`; system tab uses `updateNoticeIsRead`, campus-announcement tab uses `updateNoticeMutuaById`. Both send `noticeId`. A successful update changes `isRead`; the new implementation deliberately does not copy the upstream's unconditional optimistic read mark. |
| 607152 | `gi({offset:o.value,limit:"10"})`, `data.list`; each history row opens article detail with `t.id`. Offset/limit paging is supported by the shipped caller. No verified server-history deletion contract was established. |
| 628419 / 628490 | `myLike` code 200 means liked. Toggle consumes `data.result` and `data.count`, not an invented increment/decrement. |
| 158668 | Discovery consumes `data.findList`, displays node `name`, reads dictionaries `schoolRun`/`attendanceRadius`. No arbitrary server URL is executed by the new UI. |

## New implementation decisions (not claims about the server)

- Articles, system messages, and history send real offset/limit requests; announcements use the known full-list read and are exposed in local windows explicitly.
- Long lists use indexed `LazyColumn` slots. A 1,489-item tree is a model fixture, never 1,489 eagerly composed cards.
- A read increment is attempted at most once per owner/article in an application graph, even after a timeout/cancellation. It is not automatically replayed.
- Article likes use the canonical result and allow an explicit state refresh after an ambiguous toggle.
- Server-owned tree container names are traversed structurally. Only exact product-owned labels map to existing routes; unknown leaves show metadata without executing URLs.
- History clearing means reversible **local hiding only**, scoped by owner. It does not claim to erase server records.
- Dictionary attendance radius is exposed as configuration, not silently substituted for running-track safety thresholds.

## Additional venue contract approved by Lead

The booking teammate independently extracted the same hashed source: `/run/site/addApplyFile` assignment at UTF-16 362176; form data near 364960 contains `applyId` and `userId`, file field is `file`. No successful response body shape was observed. The dedicated multipart adapter therefore allows empty HTTP 2xx as transport completion, interprets explicit business codes in known envelopes, and returns Decode for unknown nonempty bodies. It never claims an attachment ID/path; callers must refresh application `fileList` and must not auto-replay uncertain uploads. The verified times caller uses `venueId`, `dateTime`, and `applyId:""`; the previous `siteId` alias remains compatible but is not sufficient by itself.
