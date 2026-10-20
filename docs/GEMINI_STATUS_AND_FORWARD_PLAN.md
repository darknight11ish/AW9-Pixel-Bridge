# AW9-Pixel-Bridge — Status Since `v1.2.6` and the Forward Plan

**Reviewer packet.** This document covers two things: (1) everything that has happened since
`GEMINI_CONSOLIDATED_REVIEW` — the last packet produced for review — and (2) a concrete,
prioritised plan for the remaining work.

**Read this first:** the answer to (1) is short and slightly unusual. **No code changed.** The
repository has had no commits, no pull requests and no releases since that document. What exists
here is a re-verification of the live state, three documentation gaps found while re-verifying,
and the forward plan, which is the substantive half of this packet.

---

## 1. Scope and what the previous packet covered

| Packet | Covers | Status |
|---|---|---|
| `GEMINI_REVIEW_PACKET.md` | Through `v1.1.0` | Historical |
| `GEMINI_REVIEW_PACKET_v1.1.0.md` | `v1.1.0` | Historical |
| `GEMINI_FIELD_TEST_REPORT.md` | `v1.2.2` field test + adjudication | **Superseded** by the consolidated review |
| `GEMINI_CONSOLIDATED_REVIEW.md` | `v1.2.0` → `v1.2.6`, full arc | Current record of work |
| **This document** | Delta since the consolidated review, and the forward plan | Current |

The consolidated review is the authoritative record of *what was built and what was found*. This
document does not restate it; it reports what has changed since, and what should happen next.

---

## 2. The delta since the last packet: nothing changed, and that is the finding

### 2.1 Repository

```
HEAD                    6151ef2  "docs: record the v1.2.6 device verification"
commits since 6151ef2   (none)
working tree            clean
open pull requests      none
releases                v1.2.6 (latest), v1.2.5, v1.2.4, v1.2.3
tags                    v1.2.4, v1.2.5, v1.2.6 — all ancestors of main
```

`main` is identical to `origin/main`. The last three CI runs (main, the `v1.2.6` tag, and PR #11)
all completed successfully.

### 2.2 Live environment, re-verified

The release was installed and the environment re-checked rather than assumed:

| Check | Observed |
|---|---|
| Device attached | OnePlus 10T `CPH2419` (Android 15 / API 35) |
| Installed build | `versionCode=11`, `versionName=1.2.6` — matches the published release |
| Health Connect permissions | 18 granted |
| Foreground service | `isForeground=true`, `foregroundId=1001` |
| HTTP listener | `tcp6 [::]:8484 LISTEN` |
| Live endpoint | `GET /status` → `200 {"status":"online","bleConnected":false,"bpm":0,"hcSynced":0}` |
| Test suite | 130 tests |

So the shipped state is intact and live, but **no development occurred** in the interval.

### 2.3 Three documentation gaps found while re-verifying

These are real inconsistencies, not cosmetic:

1. **`README.md` §8 is now false.** It states: *"The APK has been compile-, unit-test- and
   type-check-verified only; it has never run on a physical Pixel 9 … Unverified without a device:
   real GATT behaviour, the hour-aligned step windows and millisecond record IDs against a live
   Health Connect provider …"* Every one of those things **has** now been exercised on real
   hardware across `v1.2.3`–`v1.2.6`. The README understates what is verified, which is its own kind
   of inaccuracy.
2. **`docs/AW9_PIXEL_BRIDGE_ENGAGEMENT_REPORT.md` stops at `v1.2.1`** (§14). Rounds `v1.2.2` through
   `v1.2.6` are not in it. The consolidated review covers them, but the engagement report — the
   long-form internal document — does not.
3. **`GEMINI_FIELD_TEST_REPORT.*` is superseded** and still sits beside the consolidated review,
   which invites a reviewer to read a document whose §7 was corrected elsewhere.

### 2.4 Branch housekeeping

Four local branches remain from earlier workstreams (`feat/ble-http-step-hardening`,
`feat/dashboard-token-hotspot-ui`, `feat/shortcuts-autoexport-tests`, `feat/watchos-xcodegen-and-docs`).
`git branch --no-merged main` returns nothing, so all four are **fully merged** and can be deleted
without loss. No stale remote branches remain.

---

## 3. Where the project actually stands

A one-paragraph summary for context, because the forward plan only makes sense against it.

Six releases shipped. The most recent, `v1.2.6`, has 130 unit tests, zero lint errors, both CI jobs
green, a stable release signing key, and 5,000-record imports verified end-to-end on real hardware
with value-level read-back from the Health Connect provider. Four independent defect-discovery
mechanisms were used across the arc — a real compiler, a real CI runner, adversarial read-only
audits, and a physical device — and each found defects the others could not. The consolidated review
records all of it, including four claims from an earlier external review that were falsified by
testing.

The single largest untested surface is unchanged: **no Apple Watch or iPhone has ever been
connected to this system.**

---

## 4. Risk register: what is still unverified, and why it cannot be closed here

| ID | Unverified | Why it matters | Why it is open |
|---|---|---|---|
| R1 | Apple Watch → iPhone → phone end-to-end | The entire product premise | Needs Mac + Xcode + Apple ID + iPhone + Watch |
| R2 | The two no-Xcode ingestion paths on a real iPhone (Shortcuts, Health Auto Export) | Most users will use one of these | Needs a real iPhone and the paid HAE app |
| R3 | BLE `0x180D` / `0x1814` against a real third-party strap | The parser and connection lifecycle are only unit-tested | Needs a chest strap or RSC sensor |
| R4 | Pixel 9 specifically | OEM/permission behaviour differs from OnePlus | No Pixel 9 available |
| R5 | A real Apple Health `export.xml` (GB-scale) | Import path only proven with synthetic files up to 5,000 records | Needs a real export |
| R6 | Battery drain, hours-long background survival, OEM process killing | Foreground service is designed for this but unproven | Needs long-running time on the target device |
| R7 | Webhook re-delivery duplicates records | Measured: two identical deliveries added 500 m of duplicate distance | Design decision, not a bug to patch silently |
| R8 | Unaggregated sleep segments use only the last segment | Could under-count a night | Needs a real HAE segment payload to model correctly |
| R9 | `Metadata` labels every record as `TYPE_WATCH` / "Apple Watch" | Misattributes webhook and XML-imported data | Design decision |
| R10 | Rate limiting above ~10 chunks | Backoff exists but has never fired | Needs an import large enough to trip it |

**Important architectural note for whoever tests R1:** the watch app **cannot** serve BLE.
`README.md` §1 records that watchOS does not expose the CoreBluetooth peripheral role — proven by
compiling against the real watchOS SDK in CI, not by reading documentation. So "Apple Watch
integration" means the watchOS companion **POSTs over the LAN**; the BLE paths target third-party
straps only. A test plan that expects the watch to act as a BLE heart-rate peripheral is testing
something the platform forbids.

---

## 5. Forward plan

Ordered by value per unit of effort. Effort is a rough judgement, not a commitment.

### Phase 1 — Hardware validation (highest value; nothing else can substitute)

**A1. Apple Watch end-to-end via the watchOS companion** — *the single most valuable next step.*
- **Prerequisite:** Mac with Xcode 16+, an Apple ID (free provisioning is enough for device
  installs), an iPhone paired to the Watch, both on the same Wi-Fi as the phone.
- **Steps:** generate the project from `watchos-companion/project.yml`; set a development team;
  install on the Watch; open the app; start streaming; post telemetry to the Android bridge.
- **Acceptance:** the Android dashboard mirrors the Watch within a few seconds; Health Connect
  records appear with correct values and timestamps and are readable back via
  `GET /verify-hc?hours=1`; control buttons (play/pause, next, camera, ring) execute; no
  duplicated or cumulative records appear over a 10-minute session (this re-tests the `v1.2.2`
  fix on real hardware); the token is enforced from the Watch.
- **Risk:** HealthKit entitlement provisioning is the most likely blocker; the Watch app also
  needs the `com.apple.developer.healthkit` entitlement that CI asserts is present.

**A2. Real iPhone ingestion without Xcode** — the paths most users will actually take.
- **Steps:** on an iPhone, run the documented Shortcut and, separately, Health Auto Export with
  *Summarize Data* both on and off; point both at the bridge.
- **Acceptance:** the summarized sleep shape is imported (validates the `v1.2.4` `totalSleep`
  fix with real data); unsummarized sleep segments produce a sane night rather than the last
  segment (this is where R8 is settled); units (`mi`, `kJ`) convert correctly.
- **Why before A1:** no Mac required, and it exercises the parser against the real producer.

**A3. BLE with a real strap** — closes R3.
- **Steps:** pair a chest strap advertising `0x180D` (and optionally an RSC sensor on `0x1814`);
  verify connect/disconnect cycles, the retry budget, and that the GATT client is closed on
  disconnect.
- **Acceptance:** heart rate appears within seconds; RR intervals produce a plausible RMSSD that
  is written to Health Connect; killing the strap's battery mid-session triggers bounded retries
  and no leaked GATT clients.

**A4. Pixel 9 validation** — closes R4 and R6.
- **Steps:** install the same `v1.2.6` APK; verify Health Connect onboarding from a clean
  install; enable the 2.4 GHz hotspot and confirm the endpoint card lists it; leave the service
  running for several hours with the screen off and confirm the listener survives.

### Phase 2 — Decisions that need the owner (not code, judgement)

**B1. Webhook idempotency (R7).** Currently two identical deliveries write two records. Options:
(a) derive `clientRecordId` from producer timestamp + metric + source when the producer supplies
one, falling back to a short time bucket; (b) leave as-is and document "at least once" semantics;
(c) dedupe on the phone with a short-lived seen-window. **(a) changes record identity on upgrade**
and needs a migration story; it is the option the previous reviewer recommended, and it is the one
with the most risk.

**B2. Metadata source labelling (R9).** Every record claims `TYPE_WATCH` / "Apple Watch".
Webhook- and XML-imported data is not from a watch. Options: label by source (BLE → watch,
webhook → phone, XML import → phone), or use `TYPE_UNKNOWN`.

**B3. Unaggregated sleep segments (R8).** Currently the last segment is used. Summing only
"asleep" segments (ignoring *Awake* / *In Bed*) is more correct but needs a real payload to model,
and risks double-counting if a producer ever sends both shapes.

**B4. Keep or gate `GET /verify-hc`.** It is a diagnostic that reads Health Connect history. It is
behind the token and the private-LAN check, but it is still a surface a diagnostic does not need in
a release build.

**B5. Release signing posture.** A public, repository-committed sideload key is intentional and
documented. If the app is ever to be distributed differently, this must be revisited before the
first such release — changing keys afterwards forces an uninstall for every user.

### Phase 3 — Hardening that needs no hardware

**C1. Make CI install and upgrade the APK.** The release-signing defect (§15 of the consolidated
review) was invisible to CI because CI only builds. Adding an emulator job that installs `v(N)` and
then installs `v(N+1)` over it would have caught it automatically. This is the highest-value
non-hardware item: it closes an entire class, not one bug.

**C2. Instrumented (device) tests.** The suite is JVM-only. A small `androidTest` set for the
permission-gated service start and a Compose smoke test would cover the paths that unit tests
structurally cannot.

**C3. Swift tests for the watch app.** There are currently **zero** Swift tests. `XCTest` around
the payload builder (sentinels, dirty-field filtering, battery encoding) would be cheap and would
have caught the `v1.2.2` cumulative-payload defect earlier.

**C4. Exercise the rate-limit backoff (R10).** Raise the 5,000-record import cap behind a test
flag and import 20,000+ records to force `ERROR_RATE_LIMIT_EXCEEDED` and prove the retry works.

**C5. Re-test screen-off delivery properly.** The `WifiLock` recommendation was tested for three
minutes of doze and did not reproduce. A 30–60 minute test on the Pixel, with the app in the
background, would settle whether a wake lock is needed. Do not add one before that.

**C6. Fix the stale documentation** (§2.3): correct README §8, extend the engagement report, remove
the superseded field-test report.

**C7. Delete the four merged branches** (§2.4).

### Phase 4 — Optional product work (only if wanted)

**D1.** Additional Health Connect types (e.g. VO₂ max, wrist temperature) if the owner wants them.
**D2.** A configurable record-id scheme and dedupe window, once B1 is decided.
**D3.** Export the in-app diagnostic log for support.

---

## 6. Recommended sequence, and what can start now

**If hardware is available:** A2 → A1 → A3 → A4. A2 first because it needs no Mac and gives real
producer payloads that inform B3 immediately.

**If hardware is not available:** C1 → C3 → C4 → C6/C7. C1 is the strongest single item: it closes
the class of defect that only appears when an artifact is actually installed.

**What I would not do:** add features (Phase 4) before A1/A2. The product's core premise is
unvalidated; more surface would multiply untested claims.

---

## 7. Definition of done for the engagement

The engagement should be considered complete when all of the following hold. None of them are true
today.

1. One real Apple Watch has streamed live heart rate into the Android app and the resulting Health
   Connect records have been read back and spot-checked for value and timestamp correctness.
2. At least one no-Xcode path (Shortcut or Health Auto Export) has been validated with a real
   payload, including the summarized sleep shape.
3. A real Apple Health export (≥ 100 MB) has been imported without data loss, with the record count
   reconciled against the file.
4. CI installs one release over the previous one, so upgrade regressions are caught automatically.
5. The `v1.2.6` and later releases have been installed on the target Pixel 9 and left running for a
   full day without the service dying.

---

## 8. Questions for the reviewer

1. **Prioritisation.** Is the A2-after-A1 order right, or should the watch path be proven first
   because it is the product's premise?
2. **B1 (idempotency).** Is a producer-timestamp-derived `clientRecordId` worth the migration risk,
   and what migration would avoid duplicating or collapsing existing history on upgrade?
3. **B2 (metadata).** Is labelling webhook and XML-imported records as `TYPE_WATCH` defensible, or
   does it mislead downstream Health Connect readers enough to matter?
4. **B3 (sleep segments).** How should an unaggregated segment list be treated, and what would you
   use to distinguish "one row per night" from "one row per segment" without guessing?
5. **B4 (`/verify-hc`).** Keep it in release builds, or gate it?
6. **C1 (CI install/upgrade).** Is an emulator-based install-and-upgrade job worth its CI cost, or
   is a cheaper check (e.g. asserting a stable signing certificate per release) sufficient?
7. **Risk blind spots.** Given the risk register in §4, is there an unverified area that is being
   under-weighted?
8. **Anything in §5 that should be dropped?** Nominate one item you would cut.

---

## 9. Appendix — reproducing the state described above

```bash
git log --oneline -1                     # 6151ef2
git log --oneline 6151ef2..HEAD          # (empty)
git status --short                       # (clean)
gh pr list --state open                  # (none)
gh release list --limit 3                # v1.2.6 latest

adb devices -l                           # CPH2419 attached
adb shell dumpsys package com.bridge.applewatch | grep -E 'versionCode|versionName'
#   versionCode=11 / versionName=1.2.6
adb shell "dumpsys package com.bridge.applewatch | grep -c 'android.permission.health.*granted=true'"
#   18
adb shell "netstat -tln | grep 8484"     # tcp6 [::]:8484 LISTEN
curl -s http://<phone-ip>:8484/status     # {"status":"online",...}
curl -s "http://<phone-ip>:8484/verify-hc?hours=2160"
git branch --no-merged main              # (empty — the four local branches are merged)
./gradlew testDebugUnitTest              # 130 tests
```

*No claim in this document is inferred. The repository, release and device facts were read from
`git`, the GitHub API and the attached OnePlus 10T; the documentation gaps were read from the
files themselves. The forward plan is a proposal, not a record of work done.*
