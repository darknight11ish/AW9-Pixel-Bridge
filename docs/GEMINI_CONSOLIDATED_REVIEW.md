# AW9-Pixel-Bridge — Consolidated Review: `v1.2.0` → `v1.2.3`

**Purpose.** One document covering every round of work since the `v1.1.0` review packet, so a
reviewer can judge the whole arc in a single pass. It supersedes `GEMINI_FIELD_TEST_REPORT.md`,
which covered only the final round and treated `v1.2.2` as its baseline rather than as work to
be reviewed.

**What this document is not.** It is not a reprint of the earlier packets. It states what each
round changed, what was found, what the evidence was, and — where it matters — what was
believed and turned out to be wrong. Where a claim was established by executing something
(a compiler, a CI runner, a physical device), the command or observation is quoted.

**Reviewer's task.** Check the reasoning, challenge the severity calls, and answer the
questions in §11. §8 and §9 are the parts most worth attacking: they describe where the test
strategy has been blind.

---

## 1. Executive summary

Four releases in roughly one day of work, each driven by a different discovery mechanism:

| Release | Mechanism that found the defects | Tests | Headline outcome |
|---|---|---|---|
| `v1.2.0` | A written brief, plus real CI | 84 | Four PRs opened, watched green, squash-merged, released |
| `v1.2.1` | **Three independent adversarial audits** | 113 | 45 findings; two defects that had shipped silently since `v1.0.x` |
| `v1.2.2` | A **fourth audit** narrowed to one question | 115 | 4 high-severity data-integrity defects, one of them in the `v1.2.1` fix itself |
| `v1.2.3` | **The first physical-device field test** | 125 | 4 defects that 115 passing tests could not reach |
| `v1.2.4` | **An external review of this document** | 130 | Read-back verification; 4 verified fixes, 1 falsified claim |

The pattern is the interesting part, and it is consistent: **each round's most valuable
finding came from a mechanism the previous round did not use.** Reading the code found some.
A real compiler found more. A real CI runner found CI-only defects. Adversarial readers found
what reading could not. A real phone found what JVM tests structurally cannot.

The final round is also the one that produced the most humbling result: a defect that made
the app **silently discard a user's health data while reporting success** passed 115 unit
tests, because the test environment could not make the health-write call fail.

---

## 2. System under test

| Item | Value |
|---|---|
| Application | `com.bridge.applewatch` — "AW9 Pixel Bridge", an Android↔watchOS health bridge |
| Platform | Android 14+ (`minSdk 34`, `targetSdk 35`); watchOS 10 companion |
| Stack | Kotlin, Jetpack Compose, Health Connect `connect-client:1.1.0`, Gradle 8.11.1, AGP 8.9.1, JDK 17 |
| Releases | `v1.2.0`, `v1.2.1`, `v1.2.2`, `v1.2.3` — all published with `app-debug.apk` and `app-release.apk` |
| Current source | `main` at the `v1.2.5` merge / tag `v1.2.5` |
| Final state | **130 unit tests**, Android Lint **0 errors**, both CI jobs green, working tree clean |

### 2.1 Release ledger (all verified from the GitHub API, not from notes)

| Tag | Commit | Published | `app-release.apk` | Tests |
|---|---|---|---|---|
| `v1.2.0` | `b8340c2` | 2026-10-16 18:08 | 2,517,316 B | 84 |
| `v1.2.1` | `8f7d181` | 2026-10-16 19:29 | 2,518,256 B | 113 |
| `v1.2.2` | `d9860c7` | 2026-10-16 20:57 | 2,518,256 B | 115 |
| `v1.2.3` | `c753132` | 2026-10-17 07:57 | 2,534,644 B | 125 |
| `v1.2.4` | `aa19108` | 2026-10-17 09:38 | 2,534,644 B | 130 |
| `v1.2.5` | `0da58e9` | 2026-10-17 10:34 | see release | 130 |

`v1.2.1`/`v1.2.2` and `v1.2.3`/`v1.2.4` have release APKs of **identical byte length but different
SHA-256**;
each tag was verified to contain its own changes (e.g. `plausibleLong` is present at `v1.2.2`
and absent at `v1.2.1`), so this is a size coincidence, not a mis-published artifact.

### 2.2 Codebase metrics

56 tracked files, 1,375,665 bytes, 20,472 lines (reproducible via `scripts/verify_repo_metrics.py`).

---

## 3. `v1.2.0` — four PRs, CI-first

A follow-up brief prescribed four file-disjoint workstreams, then instructed merging to `main`
**before** opening the PRs — which makes the PRs unopenable, because this repository's CI only
runs on `main` pushes, `v*` tags and pull requests. The loop was run in the order the CI
triggers actually require: **push → open PR → watch checks → squash-merge.**

| PR | Scope |
|---|---|
| [#1](https://github.com/darknight11ish/AW9-Pixel-Bridge/pull/1) | Endpoint enumeration plus an `ON_RESUME` refresh |
| [#2](https://github.com/darknight11ish/AW9-Pixel-Bridge/pull/2) | Bounded permit wait, `503` overload response, concurrency test |
| [#3](https://github.com/darknight11ish/AW9-Pixel-Bridge/pull/3) | Parser edge cases plus the first `HealthConnectManagerTest` |
| [#4](https://github.com/darknight11ish/AW9-Pixel-Bridge/pull/4) | watchOS vitals-failure surfacing plus project-contract CI |

**Two of the four briefs described work `v1.1.0` had already delivered.** Re-implementing it
would have been churn, so each was reduced to the genuinely missing part. This is worth a
reviewer's attention as a process question: a brief written against a stale tree produces
instructions to build things that already exist.

The one substantive new defect found in this round: `Semaphore(8)` bounded how many requests
*ran*, but the permit wait behind it was unbounded, so a flood held sockets open for as long as
the slow peers took. Fixed with a bounded wait and a `503`.

---

## 4. `v1.2.1` — the audit round (45 findings)

### 4.1 Method

Previous rounds relied on self-review plus CI. This round commissioned **three independent
adversarial read-only audits** with disjoint scopes, each instructed to construct a concrete
trigger for every finding or discard it.

| Auditor | Scope |
|---|---|
| A | Android runtime: service, policy, activity, BLE parser, control dispatcher, manifest |
| B | Health-data layer: `HealthConnectManager`, XML importer, webhook parser, build config |
| C | watchOS: Swift, XcodeGen spec, `Info.plist`, CI workflow |

**45 findings.** The auditors **converged independently**: the step record-id collision was
found by A and B separately; the SDNN/RMSSD mislabelling by A, B and C. Independent agreement
was treated as established fact and fixed first.

### 4.2 The two worst defects had shipped silently for three releases

1. **A fabricated 30 bpm heart-rate record.** `valueStr.toDoubleOrNull() ?: 0.0` followed by
   `coerceIn(30L, 240L)` meant an Apple Health `<Record>` with a *missing or non-numeric*
   `value` was written to Health Connect as a **30 bpm reading** — and a genuine out-of-range
   artefact (15 or 290 bpm) was silently rewritten into a plausible one. Same class as the
   hardcoded `spo2 = 98.0` fixed in `v1.0.1`, in a path that had never been audited. Values are
   now skipped, never substituted or clamped.
2. **A ~4 GB allocation driven by one request header.** The body buffer was sized from the
   client-supplied `Content-Length` *before any byte was read*: `CharArray(contentLength)` with
   `Content-Length: 2000000000` allocates 2 billion UTF-16 chars, i.e. ~4 GB, and killed the
   process. `OutOfMemoryError` is
   an `Error`, so `catch (e: Exception)` never saw it, and the service restarted to be attacked
   again. Bodies are capped at 256 KiB (`413` before allocation); header blocks are bounded
   (`431`).

### 4.3 Data loss

| Defect | Effect |
|---|---|
| Step deltas shared one hour-stable `clientRecordId`, version 0 | Health Connect upserts by id, so every delta in an hour overwrote the previous one — an hour could never accumulate its steps |
| The watch sent a *per-workout* step count to a phone expecting a monotonic daily counter | Every step after a workout restart was discarded until the new session passed the old total |
| The in-memory step baseline was only re-established in `onCreate` | A service running overnight kept yesterday's baseline and swallowed the new day's steps |
| `Health Auto Export` `sleep_analysis` durations are **hours**; read as minutes | A 7.5-hour night was written as a 7-minute session |
| The HR sample buffer was drained *before* the flush | When Health Connect was unavailable, every 15 s of samples was discarded forever |
| A truncated `export.xml` threw out of the whole scan | One bad byte lost every record, including those already parsed |
| Record ids ignored the source | Overlapping iPhone and Watch records either collapsed or were double-counted |

### 4.4 Mislabelled clinical data

- **SDNN written as RMSSD.** The XML importer had been gated, but both JSON paths and the watch
  wrote Apple's SDNN into `HeartRateVariabilityRmssdRecord`, which stores RMSSD.
- **Producer units ignored.** `mi`/`kJ` payloads were written with wrong magnitudes, because
  the exporter's units are per-metric user preferences.
- **Apple's UTC offset discarded** and re-derived from the phone's zone.

### 4.5 Platform ceilings (found by probing the resolved artifact)

Health Connect's own record constructors **throw** above certain limits — verified by reading
the constants out of `connect-client-1.1.0`, not from documentation:

```text
HeartRateVariabilityRmssdRecord  > 200.0 ms          -> IllegalArgumentException
StepsRecord                      > 1,000,000         -> IllegalArgumentException
DistanceRecord / ActiveCalories  > 1,000,000 (unit)  -> IllegalArgumentException
FloorsClimbedRecord              > 1,000,000.0       -> IllegalArgumentException
```

Because these throw while *building* the batch, one over-limit value rejected the whole
`insertRecords` call and lost every valid record alongside it.

### 4.6 The test-honesty finding

The auditors' sharpest criticism was about the tests themselves: **the manager's ten sentinel
gates had zero coverage.** They were reachable only through a live Health Connect client, so
*deleting all ten guards kept the suite green.* The contract the parser tests claimed was
enforced "downstream" was enforced nowhere.

`buildTelemetryRecords()` was made a pure internal function and every boundary pinned. Two
further test defects were found and fixed: the XML importer tests had been exercising **stubbed**
Android XML (`Xml.newPullParser()` returns defaults under Robolectric), so they observed zero
records and passed for the wrong reason; and a record-id test was a tautology.

---

## 5. `v1.2.2` — data integrity (fourth audit)

A narrower audit asked exactly one question: *can this app write a record that is fabricated,
mislabelled, mis-scaled or silently lost?* It found four high-severity items the first three
audits had not reached — **including one where the `v1.2.1` fix was incomplete.**

### 5.1 CRITICAL — the watch re-sent cumulative and stale metrics as fresh readings

`postTelemetryToPixel` always serialised every field, and the phone writes whatever it receives
as a new record stamped `now`. But `activeCalories`/`distanceMeters`/`floorsClimbed` are
HealthKit **workout-cumulative sums**, and `spo2`/`restingHeartRate`/`respiratoryRate` are
**last-known readings**. A 30-minute run with ~200 heart-rate samples therefore wrote ~200
`DistanceRecord`s whose values grew to the whole workout total (Health Connect sums distance and
energy across records), and one SpO₂ reading became hundreds of records at times no measurement
was taken.

`v1.2.1` had redirected the *control buttons* off `/telemetry`; this per-sample callback still
did exactly what that fix's own comment condemned. The watch now sends only values that changed,
and nothing at all when nothing changed. `steps` is still sent as the cumulative daily total
because the phone computes its own delta.

### 5.2 HIGH — HRV bound left at 250 ms in two places

`v1.2.1` introduced `MAX_HRV_MS = 200` but only wired it into one gate. `writeHrvRmssd` and the
XML SDNN branch kept `1..250`. `HeartRateVariabilityRmssdRecord` throws above 200 ms, and the
RMSSD path had no `try/catch` while its caller is fire-and-forget — so an RR pair yielding e.g.
230 ms escaped as an **uncaught coroutine exception** rather than a skipped record.

A test named `hrv is gated to the 1 to 250 range` actually pinned 200. That stale name is how
the mismatch survived the previous round, and is an argument for test names that cannot drift
from their assertions.

### 5.3 HIGH — the step baseline was consumed before the write

It was advanced *and persisted* before `writeTelemetryPayload`; when that wrote nothing (Health
Connect absent returns 0 without throwing) the delta was already spent, and because it was
persisted a restart could not recover it either. Steps accrued during an outage were
unrecoverable.

### 5.4 MEDIUM — three more

- A **finite-but-oversized** `steps` value (`1e300`) saturated to `Long.MAX_VALUE` through
  `toLong()`, setting the persisted baseline to `Long.MAX_VALUE` and making every later delta
  zero for the rest of the day — the same harm the non-finite guard exists to prevent, reached
  through a finite value.
- The XML importer applied only *lower* bounds, so a value above Health Connect's ceiling threw
  **inside the scan** and abandoned the rest of the file.
- `insertBatchRecords` had **no per-chunk `try`/`catch`**: the first exception aborted the loop,
  losing every later chunk *and* the already-committed count — a 5,000-record import failing on
  chunk 3 reported total failure while 1,000 records were in Health Connect.

---

## 6. `v1.2.3` — the first physical-device field test

### 6.1 Method

`v1.2.2` was installed on **real hardware** and exercised end to end: OnePlus 10T (`CPH2419`),
**Android 15 / API 35**, sideloaded via `adb`, HTTP server driven from a host on the same
Wi-Fi (`192.168.0.172` from `192.168.0.191`).

This is the round that mattered most, and the reason is structural rather than accidental.

### 6.2 Why 115 passing tests could not have caught this

Under Robolectric the `HealthConnectClient` is `null`, so `writeTelemetryPayload` **returned 0
without throwing** and every error path in the service was dead code. The test suite could prove
the *gates* were right — and after `v1.2.1` they genuinely were — but it could not prove the
*error handling* was right. Robolectric also cannot produce a `SecurityException` from a health
write, and CI built the APK without ever installing it.

### 6.3 CRITICAL — a failed write discarded the update and still said "synced"

Observed on the device with Health Connect permission ungranted:

```
POST /telemetry  ->  200 {"status":"synced"}          <- false success
GET  /status     ->  {"bpm":0}                        <- dashboard never updated
in-app log       ->  "Webhook JSON Error: SecurityException: Caller requires
                      android.permission.health.WRITE_RESTING_HEART_RATE …"
                      (representative: whichever record is built first is the one named)
```

`_telemetry.update` ran **after** `writeTelemetryPayload` inside the same `try`, so the exception
skipped it; the response body was a **hardcoded** `"synced"`; and the log misattributed a *write*
failure to the *JSON*.

This is the most serious defect in the whole arc because of its shape: the sender is told the
data was stored, the user sees nothing, and no error surfaces anywhere. Silent health-data loss
with a false positive confirmation.

### 6.4 The other three field defects

| Severity | Defect |
|---|---|
| HIGH | Malformed JSON was acknowledged as success (`{not json` → `200 synced`), because the non-strict parser turned any parse failure into an empty payload |
| MEDIUM | The routing catch-all ingested **any** path and method. Verified on the device that a `GET` carrying a JSON body reached Health Connect and attempted a `StepsRecord` write |
| LOW | `/control` answered `{"status":"executed"}` for commands it explicitly ignored |

### 6.5 Found during re-test — the dashboard displayed rejected readings

A `heartRate` of 290 created **no** record (the write layer correctly refused it) but rendered as
**"290 BPM"**, because the display update only checked `> 0`. The same applied to a 100.1 % SpO₂.

### 6.6 What was fixed

- Display state is published immediately after a successful parse, before the write, and is
  filtered through the **same** ranges the write layer enforces. Bounds now live in one
  `HealthMetricRanges` object so the two layers cannot drift apart again.
- The ingest returns a **structured outcome**: `200` on success, `403
  HEALTH_PERMISSION_DENIED` on a `SecurityException`, `503 HEALTH_WRITE_FAILED` on anything
  else, `503 HEALTH_CONNECT_UNAVAILABLE` when the provider is absent. A health-write failure is
  no longer logged as a JSON error.
- The step baseline is still committed only after a successful write, so a failure cannot consume
  a delta.
- `WebhookPayloadParser.parseOrNull` distinguishes "`{}`" (valid, empty) from "`{not json`"
  (client bug); blank or unparseable bodies return `400`.
- Strict routing: `/status` GET, `/control` POST, `/telemetry` POST (`/` kept as a documented
  legacy alias), `405` + `Allow` for a wrong method, `404` for an unknown path.
- Unhandled control commands and a missing `command` field return `400 rejected`.

### 6.7 Testability fix — the part that matters for the future

`WatchBridgeService` now depends on a `HealthDataSink` interface with a test-only override, so
the failure paths are reachable from JUnit. New tests install a sink that throws
`SecurityException` and assert:

- the response is `403`, not `200`;
- the dashboard **still updates** with what arrived;
- a failed write **does not consume the step delta** (the next success writes the accumulated
  delta, verified as `5400 − 5200 = 200`).

Test count 115 → 125.

### 6.8 Every fix re-verified on the same device

| Check | Observed |
|---|---|
| `POST /telemetry`, HC permission **denied** | `403 HEALTH_PERMISSION_DENIED`, and `/status` → `bpm:72` |
| `POST /telemetry`, HC permission **granted** | `200 {"status":"synced","hcTotal":8,"hcWritten":8}` |
| Malformed JSON / unknown path / `GET /telemetry` / unknown command | `400` / `404` / `405` / `400 rejected` |
| `{"heartRate":290}` | not displayed (stayed at 72) |
| In-app log wording | `Health Connect permission denied — grant it on this device: …` |

---

## 7. An external review, and what adjudicating it showed

The field-test report was sent to an independent reviewer, which produced a forensic
adjudication. Its claims were checked against the code and, where a device could settle it,
against the device.

| Claim | Verdict |
|---|---|
| D1 failed write drops state + false 200 | **CONFIRMED** — fixed and re-verified |
| D2 malformed JSON = 200 | **CONFIRMED** |
| D3 catch-all route ingests `GET /` | **CONFIRMED, severity upgrade to HIGH justified** |
| D4 `/control` says "executed" on unknown verb | **CONFIRMED** |
| Its D0: the empty HC consent sheet is an app manifest defect (missing `START_VIEW_PERMISSION_USAGE`) | **FALSIFIED** by A/B test |
| Its D5: `hrSampleBuffer.clear()` runs before the flush | **FALSIFIED — already fixed in `v1.2.1`** |
| Its D6: `100.64.0.0/10` should be private | **NOT A DEFECT** — deliberate, tested exclusion |
| Claimed: `pm grant` can grant health permissions | **FALSIFIED** — throws |

### 7.1 The D0 falsification is the most instructive result

Both the original report and the reviewer had confident, opposite explanations for a Health
Connect consent sheet that rendered with **no permissions listed and "Allow" disabled**:

- the report: the `com.google.android.apps.healthdata` package is missing from this ROM;
- the reviewer: the app's manifest lacks `START_VIEW_PERMISSION_USAGE`, so the APEX controller
  filters out all 18 requested permissions. It rated this **CRITICAL** and supplied a
  framework-level causal chain.

A controlled experiment settled it. The app was uninstalled (resetting Health Connect grants) and
the **identical, unmodified APK** reinstalled; only orientation varied:

- **Portrait:** `Allow all`, `Activity. 0 of 4 selected`, `Active calories burned. Read Access. Off`
  — fully populated; "Allow all" then "Allow" granted **all 18** permissions, and the app
  immediately wrote records again.
- **Landscape:** no permission rows, no "Allow all", `Allow` disabled.
- Rotating back restored the list.

So it is an orientation-dependent rendering defect in Health Connect's own `PermissionsActivity`
on this device. Neither the report's explanation nor the reviewer's was right, and no app change
is warranted. Two lessons:

1. A plausible mechanism is not evidence. The reviewer's chain was coherent, specific and wrong,
   and a two-minute experiment would have settled it before it became a fix directive.
2. Reviewer claims must be checked against the repository, not a summary. The D5 claim described
   code that had not existed since `v1.2.1`.

### 7.2 What the external review got right, and it mattered

Its **testability recommendation was the most valuable single output**: extract a sink interface
and install a throwing fake so the service's failure handling becomes reachable from JUnit. That
is exactly what `v1.2.3` implements, and it is why D1 can no longer regress silently. Its
recommendation to decouple display state from the write was also correct; the implementation adds
one refinement — publish display state before the write, commit the *step baseline* only after it.

---

## 8. The test strategy, honestly assessed

The arc is 84 → 125 tests, but the number is the least interesting part.

| Round | What the tests could prove | What they could not |
|---|---|---|
| `v1.2.0` | Gates and parsers behave | Error handling; anything CI-only |
| `v1.2.1` | Gates are correct, boundaries pinned | Anything requiring a throwing provider |
| `v1.2.2` | Same, plus more parser surfaces | The same structural blind spot |
| `v1.2.3` | Write failures, routing, honesty of responses | Anything requiring a real radio or a real Apple Watch |

**The structural lesson:** a test suite that constructs its dependencies in their "unavailable"
state tests the unavailable path, not the failure path. For four releases `client == null` made
every write return `0` without throwing, so the entire error-handling surface was dead code that
the coverage tool would have reported as executed.

**A second lesson from `v1.2.1`:** tests can encode the *wrong* contract. The XML importer suite
exercised stubbed Android XML and observed zero records — passing for the wrong reason. The
record-id test asserted two literals the test itself had written. A green suite is evidence about
the tests, not about the program.

---

## 9. What is still unverified

No claim in this document rests on any of the following, and none of it has been addressed:

- **The app has never run on the intended device** (Pixel 9). Field testing used a OnePlus 10T
  running Android 15 — a superset of the target API level, so installability and platform APIs
  are representative, but OEM background management and permission UX are not.
- **No Apple Watch or iPhone has ever been involved.** No BLE connection to a real watch, no
  end-to-end watch→phone→Health Connect path. BLE scanning was verified to *start*; nothing more.
- **No rendered-UI validation** against any design. The UI was inspected structurally via
  `uiautomator` dumps and screenshots.
- **Releases are signed with the Android debug key.** That is a documented, deliberate choice so
  CI artifacts are immediately sideloadable. It means anyone can re-sign them and they cannot
  upgrade a properly-signed install. A product decision, not an oversight.
- **XML import was exercised only with a synthetic 4-record file**, not a multi-hundred-megabyte
  real Apple export.
- **Battery drain, background survival over hours, and OEM process killing** are untested.

---

## 10. Process observations worth a reviewer's attention

1. **Each round's best finding came from a new mechanism, not from more careful reading.**
   Compiler, then CI runner, then adversarial audits, then a physical device. Reading alone never
   found the worst defects.
2. **Three of four briefs and one external review contained instructions to fix things that were
   already fixed or symptoms that were misdiagnosed.** Verifying a brief before executing it was
   consistently worth more than executing it faithfully.
3. **The most dangerous defects were all "success" paths**: a fake 30 bpm record, a `200` on a
   failed write, a `200` on malformed JSON, an "executed" response for an ignored command. None
   crashed; all lied.
4. **Documentation drifted behind the code** and had to be consolidated — this document exists
   partly because three rounds of work had never been written up for review.

---

## 11. Questions for the reviewer

1. **Design of the write path.** `processWebhookTelemetryJson` now returns a structured outcome
   and publishes display state before writing. Is there a case where acknowledging receipt
   (`200`) while Health Connect is unavailable is *correct*, and the current `503` would cause
   worse behaviour for a client that retries aggressively?
2. **Status-code choice.** `403` is used for a missing Health Connect permission and `503` for
   other write failures. Is `403` right for an iOS Shortcut, which may treat it as a permanent
   failure and stop retrying?
3. **Idempotency.** Webhook `clientRecordId`s are timestamp-based, so re-delivering the same body
   writes duplicates (the XML importer is deterministic and idempotent). Should webhook ids be
   derived from content instead, accepting that two genuine identical readings a second apart
   would then collapse into one?
4. **The `350 ms` RR-interval filter** used for RMSSD accepts pairs whose difference is up to
   350 ms, which can yield RMSSD above Health Connect's 200 ms ceiling. Is dropping such a value
   the right behaviour, or should the filter be tightened so the value is never computed?
5. **Metadata semantics.** Every record is written with `Metadata.manualEntry`, so Health Connect
   presents device-measured and imported data as hand-entered. Every `autoRecorded` overload
   that also takes a `clientRecordId` requires a non-null `Device`; constructing one correctly
   was not attempted. Is guessing at that `Device` worth it, or should this stay explicit?
6. **Health Auto Export sleep.** The **aggregated** shape (`totalSleep`, no `qty`) is dropped
   entirely, and unaggregated segments are currently summed as a whole night rather than per
   segment. Is dropping the aggregated shape defensible, or is that silent data loss?
7. **The remaining blind spot.** Given §8 and §9, what is the highest-value next test that does
   not require a physical Apple Watch?
8. **Anything missed.** Given the evidence in this document, is there a plausible defect class in
   these paths that none of the four mechanisms has probed?

---

*Compiled from the repository at `c753132` (tag `v1.2.3`). Every release figure was read from the
GitHub API; every defect claim was either reproduced by a failing observation or by a test that
now pins it. Where a claim could not be verified it is listed in §9 rather than asserted.*

---

## 14. `v1.2.4` — acting on the external review of this document

The review of this document approved `v1.2.3` and answered the eight questions in §11 with
seven concrete recommendations. Each was verified before being acted on; two did not survive.

| # | Recommendation | Action | Outcome |
|---|---|---|---|
| Q7 | Verify by **reading records back** from the provider, not just that `insert` returned | **Implemented** | First true end-to-end proof in this project (§14.1) |
| Q6 | Summarized sleep (`totalSleep`, no `qty`) is silent data loss | **Implemented** | Verified on device: the shape now writes a record |
| Q5 | Label records `autoRecorded` with a `TYPE_WATCH` `Device`, not `manualEntry` | **Implemented** | `Device`'s optional fields confirmed by compiling, not assumed |
| Q1/Q2 | Keep `403`/`503`; add `Retry-After` | **Implemented** | `Retry-After: 30` on retryable failures |
| Q8.1 | Back off and retry on `ERROR_RATE_LIMIT_EXCEEDED` | **Implemented** | Constant confirmed in the platform jar first |
| Q4 | Keep the 350 ms RR filter, drop final values > 200 ms | **No change needed** | Code already did exactly this; verified |
| Q3 | Content-derived webhook ids for idempotency | **Deferred deliberately** | Changes record identity on upgrade; needs a decision, not an edit |
| Q8.3 | Add a `WifiLock` so screen-off delivery works | **Falsified, not implemented** | Tested: delivery did **not** fail with the screen off |

### 14.1 The read-back verification is the most valuable outcome of the whole arc

Every prior verification stopped at "the insert call returned". That is not the same as "the
provider stored it": a `clientRecordId` collision upserts, and a rejected record disappears
silently. `HealthConnectManager.countRecordsSince()` plus `GET /verify-hc` reads records back
out of Health Connect, and this is what it showed on the OnePlus 10T:

```
POST /telemetry -> {"status":"synced","hcTotal":9,"hcWritten":9}

GET  /verify-hc  -> {"heartRate":2,          // was 1 before the POST
                     "steps":1, "distance":1, "activeCalories":1,
                     "oxygenSaturation":1, "floorsClimbed":1, "restingHeartRate":1,
                     "respiratoryRate":-1, "sleep":-1,
                     "readBackTotal":8}
```

**Eight of nine submitted records were read back out of the provider.** `sleep` and
`respiratoryRate` report `-1`, and the response carries an explicit `sentinelMeaning` saying
so: the app deliberately holds only WRITE permission for those two types, so probing a read
would otherwise be reported as a failure. That distinction is the difference between a
diagnostic and a false alarm.

### 14.2 A claim that did not survive testing

The review rated a `WifiLock` as "critical for reliability", predicting that 2–5 minutes after
the screen turns off the Wi-Fi chip enters power-save mode and drops TCP `SYN` packets on
port 8484. This was tested directly:

```
screen off, mWakefulness=Dozing, deviceidle mState=ACTIVE, after 180 s

POST /telemetry -> HTTP 200  connect=0.149s  total=0.223s   (record written)
GET  /status    -> HTTP 200  total=0.050s
```

Delivery did not fail. No wake lock was added: it would cost battery to fix something not
observed. The honest conclusion is bounded — three minutes of dozing on one OEM's build is
not a proof that screen-off delivery never fails, and a longer test (or the Pixel 9) could
still justify it. It is recorded here as *tested and not reproduced*, not as *disproved*.

### 14.3 What this changes about the document's own conclusions

§8 argued that each round's best finding came from a mechanism the previous round did not
use. `v1.2.4` continues the pattern in an uncomfortable direction: the most valuable item
came from **an external reader noticing that a whole category of verification was missing** —
not from any of the four internal mechanisms. The lesson generalises: the gap was not "a
defect nobody found", it was "a question nobody asked", and no amount of re-reading the code
would have surfaced it.

It also repeats the §7.1 lesson exactly. Of the review's recommendations, the one rated most
severe (`WifiLock`) was the one that did not reproduce, and the ones rated as workmanlike
(read-back, sleep shape, metadata) were the ones that mattered. Severity confidence and
severity accuracy are different things.

### 14.4 `v1.2.4` state

**130 tests** (was 125: summarized sleep ×2, device-recorded metadata, `Retry-After`,
`/verify-hc`), Android Lint 0 errors, both CI jobs green, release published. The 5 new tests
name the behaviour they pin, so a regression cannot be renamed away from its assertion — the
lesson from the `hrv is gated to the 1 to 250 range` incident in §5.2.

---

## 15. `v1.2.5` — a release-pipeline defect found only by installing a release

This round began as bookkeeping — deploy the published `v1.2.4` to the phone so the device
matched the artifact — and immediately failed:

```
adb: failed to install app-release.apk: Failure
[INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package com.bridge.applewatch
 signatures do not match newer version; ignoring!]
```

### 15.1 Root cause

The `release` build type was signed with `signingConfigs.getByName("debug")`. **Gradle
generates a debug keystore on demand**, so every CI runner produced its own key. Three
distinct certificates were measured:

| Build | Certificate SHA-256 |
|---|---|
| `v1.2.3` (published) | `f7dbfda60e79d1470f5ebdd71e88d7e367b2c49ce4b8b8fafd5fcca4931ba476` |
| `v1.2.4` (published) | `42a41c9428a4654e95d29c8cd8348dd783f7b1942ab6131da7c973671c63069c` |
| local build | `62ef7742e35e742a94a071bba83631fd6db78b5b50961f7b45719d736500d7b5` |

Consequence: **no release could ever update the previous one.** Every upgrade required an
uninstall, which discards the bridge token and the persisted step baseline.

### 15.2 Why four mechanisms missed it

| Mechanism | Why not |
|---|---|
| Unit tests | Never install anything |
| CI | Builds both APKs and uploads them, but never installs one over another |
| Three adversarial audits | Read-only; no artifact is ever signed or installed |
| Field test (§6) | Installed a **locally built** APK, not a published one — and always after an uninstall, so a signature mismatch could not surface |

The only thing that could find it was **installing a published artifact over an existing
install**. That step had never been performed in four releases.

It also exposes a gap in this document's own method: §6 verified behaviour on the device, but
verified it against a locally built binary. "It works on the device" and "the release artifact
works on the device" are different claims, and only the first had been checked.

### 15.3 Fix

A fixed, repository-committed sideload key (`ci/sideload.keystore`), used by CI **and** local
builds, password documented because the key is public, with `ci/README.md` stating that it must
never be used for a Play upload and that rotating it means no installed build will upgrade
again. Bumped to `v1.2.5` / `versionCode 10`.

### 15.4 Verification

```
CI-published v1.2.5 certificate : d1c1602825393a180791b78c17f27f3cee8cd41a32597512260ac3648e528101
local build certificate         : d1c1602825393a180791b78c17f27f3cee8cd41a32597512260ac3648e528101

install published v1.2.5            -> Success
install local build OVER it         -> Success
```

The last line is the property that was broken: cross-source, in-place upgrade now works.
One-off cost: any build already installed from the throwaway-key releases needs a single
uninstall to reach this lineage.

### 15.5 The generalisable lesson

Every round in this document found its best defect in the mechanism the previous round did not
use (§14.3), and this round extends the pattern to the last step of the pipeline: **building,
signing and publishing are not the same as shipping.** A release artifact has properties —
signature, upgrade path, installability — that exist only when it is installed over an existing
one, and none of the four earlier mechanisms ever did that.
