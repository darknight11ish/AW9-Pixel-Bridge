# AW9-Pixel-Bridge — Physical-Device Field Test Report

**Document purpose:** this is a review packet for an independent reviewer. It reports what happened
when the shipped `v1.2.2` release was installed and exercised on **real Android hardware** for the
first time, after five prior rounds in which every claim was based on JVM/Robolectric tests and CI.

**Reviewer's job:** check the diagnosis, challenge the severity ratings, and answer the explicit
questions in §9. Every claim in §5–§7 is backed by a command or log line that is reproduced so it can
be re-run.

**Status of the codebase at the time of the field test:** `v1.2.2`, tag `v1.2.2`, commit `d9860c7`,
115 unit tests passing, Android Lint 0 errors, both CI jobs green. None of that changed the
outcome below. **The defects in §6 are fixed in `v1.2.3`** (125 tests) and every fix was
re-verified on the same device — see §13.

---

## 1. Executive summary

The app **installs, launches, runs its foreground service, binds its HTTP server on a real LAN, and
authenticates correctly** on Android 15. Those parts are genuinely verified, not inferred.

Field testing also found **four defects that 115 passing unit tests could not catch**, one of them
serious:

1. **CRITICAL — a Health Connect write failure silently discards the entire telemetry update while the
   webhook still answers `{"status":"synced"}`.** The dashboard then shows `0 BPM`, `0 steps` and no
   battery even though the phone received and parsed valid data. A client (an iOS Shortcut, or the
   watch) is told everything succeeded.
2. **HIGH — malformed JSON is accepted as success** (`HTTP 200 {"status":"synced"}`).
3. **MEDIUM — any unrecognised path or method is treated as a telemetry POST**, so `GET /` and
   `GET /unknown` both return a "synced" telemetry response instead of 404/405.
4. **LOW — `/control` reports `"status":"executed"` for commands it explicitly ignored.**

Separately, the test device **cannot grant Health Connect permissions at all** (see §7). That is an
environment limitation, not an app defect, but it is what exposed defect 1.

**The headline lesson:** the write path's failure handling — not its happy path — is what the unit
tests never modelled. In JVM tests `HealthConnectClient` is `null`, so every write short-circuits and
the code after the write is never reached with a throwing client.

---

## 2. System under test

| Item | Value |
|---|---|
| Application | `com.bridge.applewatch` ("AW9 Pixel Bridge") |
| Version | `versionName=1.2.2`, `versionCode=7` (verified on device via `dumpsys package`) |
| Artifact | release `app-release.apk`, 2,518,256 bytes, SHA-256 `636859cd0694f238edb19d793ed355faefb37f94113b02653967b67939a7b2c3` |
| Install method | `adb install -r` (sideloaded; the release is debug-signed by an earlier documented decision) |
| minSdk / targetSdk | 34 / 35 |
| Source commit | `d9860c7` (tag `v1.2.2`) |

---

## 3. Device and environment (all read from the device)

| Property | Value |
|---|---|
| Model | OnePlus 10T, `CPH2419`, device `OP5552L1`, manufacturer `OnePlus` |
| Android | **15** (`ro.build.version.release=15`), **API 35** (`ro.build.version.sdk=35`) |
| Build | `S.2479e09-16953-1bdd09`, hardware `qcom` |
| Display | 1080 × 2412, density 480; the app renders in **landscape** on this device |
| Phone IP | `192.168.0.172/24` on `wlan0` (Wi-Fi "TP-Link_40A8"); also a VPN on `tun0` (`100.124.30.77`) |
| Host IP | `192.168.0.191` (Windows 11, Wi-Fi) — same `/24`, so traffic is genuinely over the LAN |
| Health Connect | controller from APEX: `/apex/com.android.healthfitness/priv-app/HealthConnectControllerGoogle@372042540`, `versionCode=37`, `versionName=17`; provider binder present as `healthconnect: [android.health.connect.aidl.IHealthConnectService]` |

**Note on the device substitution:** the project is written for a Pixel 9. This test ran on a OnePlus
10T because that was the attached device. Android-version-wise it is a *superset* of the target
(API 35 ≥ 34), so installability and platform APIs are representative. OEM-specific behaviour
(aggressive background management, permission UX) is **not** representative of a Pixel.

---

## 4. What changed on the device before testing

Permissions were granted partly by hand and partly through the real dialog, because the goal was to
reach the app's behaviour rather than to re-test Android's permission UI:

```
adb install -r app-release.apk                    # Success
adb shell am start -n com.bridge.applewatch/.MainActivity
adb shell pm grant com.bridge.applewatch android.permission.BLUETOOTH_SCAN
adb shell pm grant com.bridge.applewatch android.permission.BLUETOOTH_CONNECT
adb shell pm grant com.bridge.applewatch android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.bridge.applewatch android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant com.bridge.applewatch android.permission.POST_NOTIFICATIONS
```

The app then started its foreground service (`isForeground=true`, `foregroundId=1001`, notification
channel `aw9_pixel_bridge_channel`) and began listening:

```
tcp6   0   0  [::]:8484   [::]:*   LISTEN
```

`Health Connect` permissions were **not** grantable — see §7.

---

## 5. Verified working (with evidence)

Each row was executed during this session against the device.

| # | Feature | Evidence |
|---|---|---|
| W1 | Installs and launches on Android 15 without crashing | `versionCode=7`; process alive; empty `logcat -b crash` |
| W2 | Foreground service + persistent notification | `dumpsys activity services` → `isForeground=true`, channel `aw9_pixel_bridge_channel` |
| W3 | HTTP server binds and is reachable over the real LAN | from host: `GET http://192.168.0.172:8484/status` → `HTTP 200 {"status":"online","bleConnected":false,"bpm":0,"hcSynced":0}` |
| W4 | Endpoint discovery shows the correct live IP | UI: `Wi-Fi (wlan0): http://192.168.0.172:8484/telemetry` |
| W5 | Token auth: missing token rejected | `curl -X POST .../telemetry` → `HTTP 401` |
| W6 | Token auth: correct token accepted | `-H "X-Bridge-Token: fieldtest123"` → `HTTP 200` |
| W7 | Token auth: wrong token rejected | `-H "X-Bridge-Token: wrong"` → `HTTP 401 {"status":"unauthorized","code":401}` |
| W8 | Token auth: `/status` is also protected | with token set, `GET /status` without token → `HTTP 401` |
| W9 | Token-in-body fallback works | `{"authToken":"fieldtest123","steps":3000}` → `HTTP 200` |
| W10 | Auth badge reflects storage immediately | UI flipped to `● Token Auth Active (X-Bridge-Token required)` on Save, and `Cleared X-Bridge-Token (LAN-only mode)` on Clear — this validates the v1.2.1 auth-badge fix on real hardware |
| W11 | 413 on oversized body | 300 KB body → `HTTP 413 Payload Too Large`; 250 KB (under the 256 KiB cap) → `HTTP 200` |
| W12 | 431 on oversized header | 20,000-char header → `HTTP 431 Request Header Fields Too Large` |
| W13 | CORS preflight | `OPTIONS` with `Origin`/`Access-Control-Request-Method` → `HTTP 204`, `Access-Control-Allow-Origin: *` |
| W14 | Control: media play/pause | `{"command":"MEDIA_PLAY_PAUSE"}` → `{"status":"executed","action":"Toggled Pixel 9 Media Play/Pause"}` |
| W15 | Control: media next | `MEDIA_NEXT` → `"Skipped to Next Track"` |
| W16 | Control: camera shutter | `CAMERA_SHUTTER` → `"Camera shutter broadcast attempted (no confirmation available)"` |
| W17 | Control: find-phone alarm | `FIND_MY_PIXEL` → `"Triggered Find My Pixel 9 Alarm (5s)"`; UI `Last Watch Control Action` updated |
| W18 | BLE scanning starts | UI: `Scanning BLE 0x004C Continuity & 0x180D GATT...`, `Continuity State: Passive Scan Active` |
| W19 | Dashboard renders all metric cards | `LIVE HEART RATE`, `HRV (RMSSD)`, `STEPS TODAY`, `ACTIVE ENERGY`, `BLOOD OXYGEN (SpO2)`, `DISTANCE & FLOORS`, `SPEED & CADENCE`, `RESTING HR & SLEEP` all present with units |
| W20 | XML import entry point opens the system file picker | tapping `Import export.xml` → `com.google.android.documentsui/.../PickActivity` |

**Not verified:** whether the media keys actually changed playback (no music was playing, so there was
no observable media session), and whether the camera broadcast reached a camera app (the app itself is
honest that it cannot confirm this).

---

## 6. Defects found

### D1 — CRITICAL: a Health Connect write failure discards the whole telemetry update, and the webhook still reports success

**Reproduction (this exact sequence was run):**

```bash
# valid JSON, all metrics in range
curl -X POST http://192.168.0.172:8484/telemetry \
     -H "Content-Type: application/json" \
     --data-binary @payload.json
# -> {"status":"synced","hcTotal":0}

curl http://192.168.0.172:8484/status
# -> {"status":"online","bleConnected":false,"bpm":0,"hcSynced":0}
```

`payload.json` was:

```json
{"heartRate":72,"steps":5200,"spo2":98.0,"activeCalories":310.5,"distanceMeters":2400.0,
 "floorsClimbed":7,"restingHeartRate":52,"respiratoryRate":14.5,"sleepMinutes":420,"battery":62}
```

The app's own in-app log (read from the UI) showed, at the same moments:

```
[23:46:49] Webhook JSON Error: android.health.connect.HealthConnectException:
           java.lang.SecurityException: Caller requires
           android.permission.health.WRITE_STEPS to write record type
           class android.health.connect.datatypes.StepsRecord
```

**Both symptoms, one cause.** In `WatchBridgeService.kt`:

* L1086 — `val writtenCount = healthManager.writeTelemetryPayload(payload, advance.delta)`
* L1108 — `_telemetry.update { cur -> cur.copy(...) }`  ← the UI state, **after** the write
* L1125–1127 — `catch (e: Exception) { logEvent("Webhook JSON Error: ${e.message}") }`
* L1006 — `"""{"status":"synced","hcTotal":${...}}"""`  ← **hardcoded**, evaluated regardless of outcome

So when `insertRecords` throws (permission denied, provider unavailable, any `HealthConnectException`),
control jumps to the `catch` **before** `_telemetry.update` runs. Consequences:

* The dashboard and `/status` keep their previous values — for a fresh install, permanently zero —
  even though valid telemetry arrived and was parsed correctly.
* The HTTP response is still `200 {"status":"synced"}`, so the sender cannot detect the failure.
* The log line says **"Webhook JSON Error"**, which misattributes a *write* failure to the *JSON*, so
  an operator reading the log is pointed at the wrong subsystem.

I confirmed the parser is not at fault by running the identical payload through
`WebhookPayloadParser.parse` in a JVM test: it correctly produced `heartRate=72, steps=5200, spo2=98.0,
activeCalories=310.5, distanceMeters=2400.0, floorsClimbed=7.0, restingHeartRate=52,
respiratoryRate=14.5, sleepMinutes=420, battery=62`.

**Note on interaction with the previous round.** v1.2.2 moved the *step baseline* commit to after the
write precisely so a failed write would not consume the delta (that fix works — the log shows
`Step baseline established at 1234` only when the write path returned, and the delta was retained when
the write threw). The **UI state update** was not moved with it, so the same class of bug remained one
layer up.

**Impact.** The watch or a Shortcut receives a success acknowledgement for data that was never stored,
while the phone's dashboard shows nothing. This is silent health-data loss with a false positive
confirmation — the exact failure mode the project's own audits rated highest.

### D2 — HIGH: malformed JSON is accepted as success

```
POST /telemetry   body: {not json
-> HTTP 200  {"status":"synced","hcTotal":0}
```

`JSONObject(rawJson)` fails inside `runCatching`, the parser returns a default `WebhookTelemetryPayload()`,
and the handler returns "synced". A client sends a broken automation and is told it worked. Expected: `400`.

### D3 — MEDIUM: unrecognised paths and methods are treated as telemetry POSTs

```
GET /          -> HTTP 200 {"status":"synced","hcTotal":0}
GET /unknown   -> HTTP 200 {"status":"synced","hcTotal":0}
POST /telemetry (empty body) -> HTTP 200 {"status":"synced","hcTotal":0}
```

The dispatch is `when { GET && /status -> ...; POST && /control -> ...; else -> telemetry }`, so the
`else` is a catch-all. Any path or method is answered as a successful telemetry ingest.

**This was verified, not just read from the source.** A `GET` to `/` carrying `{"steps":9999}` was
answered `HTTP 200 {"status":"synced","hcTotal":0}` and the app's log recorded a real ingest attempt
against Health Connect:

```
[23:49:21] Webhook JSON Error: ... SecurityException: Caller requires
           android.permission.health.WRITE_STEPS to write record type
           class android.health.connect.datatypes.StepsRecord
```

So an idempotent-looking `GET` is treated as a state-changing write. Expected: `404` for unknown
paths, `405` for wrong methods.

### D4 — LOW: `/control` reports `"executed"` for commands it ignored

```
{"command":"nonsense"} -> {"status":"executed","action":"Ignored Unknown Command: nonsense"}
```

The `status` field contradicts the `action` field. A client that checks only `status` concludes the
command ran. (The four commands the watch actually sends — `MEDIA_PLAY_PAUSE`, `MEDIA_NEXT`,
`CAMERA_SHUTTER`, `FIND_MY_PIXEL` — are all handled correctly, so this is a reporting defect only.)

---

### D5 — MEDIUM: the dashboard displayed readings the write layer rejected

Found during the re-test, not in the original field session.

```
POST /telemetry  {"heartRate":290,"spo2":100.1}   -> 200 (nothing written: out of range)
GET  /status                                      -> {"bpm":290}
Dashboard                                         -> "290 BPM"
```

`buildTelemetryRecords` correctly refuses a 290 bpm record — Health Connect never sees it —
but the display update only checked `if (payload.heartRate > 0)`, so an impossible vital was
rendered as live data. The same applied to a 100.1 % SpO₂.

**Fixed in `v1.2.3`** by moving the bounds into one shared `HealthMetricRanges` object used
by both the write layer and the display, so they cannot drift apart again. Re-verified on the
device: `{"heartRate":290}` leaves the displayed value unchanged at 72.

## 7. CORRECTED: the empty consent sheet is a Health Connect layout defect, not an app defect

> **This section replaces an earlier and incorrect conclusion.** The first version of this
> report attributed the empty Health Connect consent sheet to the absence of the
> `com.google.android.apps.healthdata` package and called it a device limitation. An
> independent reviewer then proposed a different cause — a missing
> `android:permission="android.permission.START_VIEW_PERMISSION_USAGE"` on the app's
> `VIEW_PERMISSION_USAGE` activity, which would have made it an app-side manifest defect.
> **Both explanations are wrong**, and this section records the controlled experiment that
> settled it.

### What was observed originally

Requesting Health Connect permission rendered a consent sheet with **no data types listed
and a disabled "Allow" button**, so permission could not be granted by tapping:

```
'Allow AW9 Pixel Bridge to access Health Connect?'
'Choose data you want this app to read or write to Health Connect'
[ secondary_button "Don't allow" bounds=[72,927][392,1032] ]
[ primary_button_outline "Allow" bounds=[2003,927][2209,1032] ]   <-- disabled
```

Input was reaching the window (`InputDispatcher` logged the taps against
`PermissionsActivity`), so it was not a missed tap. The app declares all 18 health
permissions in its manifest and the packaged APK carries them (`aapt2 dump permissions`),
and it requests 18 — so it was not a missing-declaration problem either.

### The controlled experiment

The app was uninstalled (which resets Health Connect grants) and the **identical, unmodified
`v1.2.2` APK** was reinstalled. No code was changed at any point in this comparison — only
the device orientation was varied.

**Portrait (rotation 0) — the sheet is fully populated:**

```
'Allow all'                                          enabled=true
'Allow "AW9 Pixel Bridge" to read'                   enabled=true
'Activity. 0 of 4 selected. Read Access'
'Active calories burned. Read Access. Off'
'Don't allow'                                        enabled=true
'Allow'                                              enabled=false (nothing selected yet)
```

**Landscape (rotation 1) — on the very same dialog, the list disappears:**

```
'Allow AW9 Pixel Bridge to access Health Connect?'
'Choose data you want this app to read or write to Health Connect'
'If you give read access, the app can read new data and data from the past 30 days'
'You can learn how AW9 Pixel Bridge handles your data in their privacy policy'
'Don't allow'  enabled=true
'Allow'        enabled=false
```

No permission rows, no "Allow all". Rotating back to portrait restores the list, so the
effect is deterministic and reversible.

### Confirmation that the dialog is fully functional

In portrait, tapping **"Allow all"** then **"Allow"** granted **all 18** health permissions
(`dumpsys package` → 18 × `granted=true`), and the app immediately wrote to Health Connect
again:

```
POST /telemetry -> {"status":"synced","hcTotal":8}
GET  /status    -> {"status":"online","bpm":72,"hcSynced":8}
```

Therefore:

* It is **not** a missing `com.google.android.apps.healthdata` package. On Android 14+ Health
  Connect is the `/apex/com.android.healthfitness` mainline module, and
  `com.google.android.healthconnect.controller` is the correct package; the Play Store
  `apps.healthdata` app is the Android 13 backport and is *expected* to be absent on
  Android 15. The provider binder (`healthconnect: [IHealthConnectService]`) and the
  controller are both present and working.
* It is **not** a missing `START_VIEW_PERMISSION_USAGE` manifest attribute. The unmodified
  APK populates the full permission list and can be granted every permission.
* It **is** an orientation-dependent rendering defect in Health Connect's own
  `PermissionsActivity` on this device/OEM build. Nothing in this app's code can fix it.

**Residual risk worth knowing:** a user who rotates their phone to landscape while the
consent sheet is open cannot grant permission and gets no explanation. The app cannot detect
or work around this. It is worth reporting upstream, and it is a good argument for the app
surfacing "Health Connect permission not granted" prominently rather than relying on the
system dialog alone — which is exactly what `v1.2.3` now does (a `403` plus a dashboard that
still shows the received data).

### Why this matters for the earlier defect

The ungranted-permission state — whether caused by this layout bug or simply by a user not
having authorised Health Connect yet, which is the normal first-run state — is precisely the
condition that exposed **D1**. So even though the layout defect is not the app's, the
handling defect it uncovered is.

## 8. What could not be tested, and why

Being explicit about the boundary of this session:

| Not tested | Reason |
|---|---|
| Apple Watch → iPhone → phone end-to-end | No Apple Watch or iPhone available. BLE scanning was active but nothing of type `0x004C`/`0x180D` was in range, which is expected. |
| Any successful Health Connect write | Device cannot grant HC permission (§7). |
| Health Connect read-back / aggregate correctness | Same. |
| XML import completing | The system file picker opened, but synthetic input did not select a file reliably, so the import path was not exercised end-to-end. A test `export.xml` was pushed to `/sdcard/Download/` ready for a manual run. |
| Actual effect of media-key commands | No media playing on the device, so no session state changed. |
| Battery drain, background survival over hours, OEM process killing | Out of scope for a single session; the OnePlus battery manager actively interfered (it raised a "Charging limit" modal that stole focus). |
| Rendered UI visual correctness | Verified structurally via `uiautomator` dumps and screenshots, not against a design. |

---

## 9. Questions for the reviewer

1. **D1 root cause.** Do you agree the correct fix is to commit the telemetry state update
   independently of the Health Connect write, and to make the HTTP response reflect what actually
   happened? Is there a reason to keep the update after the write?
2. **D1 status code.** On a failed Health Connect write, what should `/telemetry` return —
   `200` with an explicit `{"status":"partial","hcWritten":0}`, or a `5xx`? The caller is often an iOS
   Shortcut, which treats any 2xx as success and may not inspect the body.
3. **D1 error classification.** Should a `SecurityException` (permission not granted) be distinguished
   from a transient provider failure, so the client knows whether to prompt the user or retry?
4. **D2.** Is `400 Bad Request` correct for unparseable JSON, given that some legacy clients may post
   `text/plain`? Should the content type be enforced?
5. **D3.** Should unknown paths return `404`, and is there a reason the catch-all was written this way?
   Should `GET` with a body ever be ingested?
6. **D4.** Should unhandled commands return `400`/`422` rather than `200 {"status":"executed"}`?
7. **§7.** Is the disabled-`Allow` Health Connect screen explainable by the missing
   `com.google.android.apps.healthdata` package, or is there an app-side cause I should investigate?
8. **Anything missed.** Given the evidence above, is there a plausible defect in these paths I have not
   looked for?

---

## 10. Why the unit tests did not catch this

This is the most useful lesson from the session, and it is worth reviewing as a process question.

* The 115 JVM tests construct `HealthConnectManager` with a **null** `HealthConnectClient`, because a
  real client cannot be constructed off-device. Every write method therefore returns early
  (`val hc = client ?: return 0`), and **the code after the write is never executed with a throwing
  client**. The tests can prove the *gates* are right (and after the last round they genuinely are),
  but they cannot prove the *error handling* is right.
* Robolectric does not model Health Connect's permission enforcement, so `SecurityException` never
  occurs in tests.
* CI builds and lints the APK but never installs or runs it.

A future test that would have caught D1 without hardware: inject a `HealthConnectClient` that throws
`SecurityException` on `insertRecords`, then assert that (a) the telemetry state still updates and
(b) the HTTP status is not a success. That requires `HealthConnectManager` to accept a client
abstraction rather than calling `HealthConnectClient.getOrCreate` internally.

---

## 11. Reproduction appendix

```bash
adb install -r app-release.apk
adb shell am start -n com.bridge.applewatch/.MainActivity
adb shell pm grant com.bridge.applewatch android.permission.BLUETOOTH_SCAN
adb shell pm grant com.bridge.applewatch android.permission.BLUETOOTH_CONNECT
adb shell pm grant com.bridge.applewatch android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.bridge.applewatch android.permission.POST_NOTIFICATIONS

adb shell netstat -tln | grep 8484            # expect: tcp6 [::]:8484 LISTEN

# W3 / D1
curl -s http://192.168.0.172:8484/status
curl -s -X POST http://192.168.0.172:8484/telemetry \
     -H 'Content-Type: application/json' --data-binary @payload.json
curl -s http://192.168.0.172:8484/status       # still bpm:0

# D2
printf '{not json' > bad.json
curl -s -X POST http://192.168.0.172:8484/telemetry \
     -H 'Content-Type: application/json' --data-binary @bad.json   # -> 200 synced

# D3
curl -s http://192.168.0.172:8484/
curl -s http://192.168.0.172:8484/unknown

# D4
curl -s -X POST http://192.168.0.172:8484/control \
     -H 'Content-Type: application/json' -d '{"command":"nonsense"}'

# W11 / W12
head -c 300000 /dev/zero | tr '\0' 'x' > big.txt ; curl -s -o /dev/null -w '%{http_code}\n' \
     -X POST http://192.168.0.172:8484/telemetry --data-binary @big.txt   # 413

# Read the app's own log (this is where the real error surfaces)
adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml .
# ... or read it from the UI: scroll to "Google Health Connect & Remote Control Receipts"
```

---

*Prepared from a live session against `com.bridge.applewatch` v1.2.2 on OnePlus 10T (CPH2419,
Android 15 / API 35). No claim in §5–§7 is inferred: each was produced by a command whose output is
quoted. The APK has still never run on a Pixel 9 and has still never exchanged data with a real Apple
Watch.*

---

## 12. Adjudication of the external forensic review

An independent reviewer produced a forensic adjudication of the first version of this report.
It upheld D1–D4, upgraded D3 to HIGH, proposed one new defect (D0) that it rated CRITICAL,
and proposed two further findings (D5, D6). Each claim was checked against the code and,
where a device could settle it, against the device. Verdicts:

| Claim | Reviewer's position | Verdict | Evidence |
|---|---|---|---|
| **D1** failed write drops state + false 200 | CRITICAL, upheld | **CONFIRMED** | Reproduced on hardware; fixed in `v1.2.3`; re-verified (§13) |
| **D2** malformed JSON = 200 | HIGH, upheld | **CONFIRMED** | `{not json` returned `200 synced`; now `400` |
| **D3** catch-all route ingests `GET /` | Upgraded to HIGH | **CONFIRMED, upgrade justified** | A `GET /` carrying `{"steps":9999}` was verified to attempt a `StepsRecord` write |
| **D4** `/control` says "executed" on unknown verb | LOW, upheld | **CONFIRMED** | Returned `{"status":"executed","action":"Ignored Unknown Command: …"}`; now `400` |
| **D0** empty HC consent sheet is an app manifest defect (missing `START_VIEW_PERMISSION_USAGE`) | Raised to CRITICAL, overturning §7 | **FALSIFIED** | Controlled A/B on the unmodified APK: the sheet is fully populated and grants all 18 permissions without that attribute. The real cause is landscape rendering (§7) |
| **D5** `hrSampleBuffer.clear()` runs before the flush | HIGH (new) | **FALSIFIED — already fixed** | `WatchBridgeService.kt:1134` snapshots without clearing and retries; samples are removed only after a confirmed write |
| **D6** `100.64.0.0/10` should be treated as private | LOW (new) | **NOT A DEFECT** | Deliberate and unit-tested exclusion; the peer check is defence in depth, not authentication |
| Claimed: `pm grant` can grant health permissions | "Bonus verification" | **FALSIFIED** | `pm grant com.bridge.applewatch android.permission.health.WRITE_HEART_RATE` throws; health permissions are granted only through the Health Connect consent flow |
| Claimed: absent `com.google.android.apps.healthdata` is a ROM defect | part of D0 reasoning | **FALSIFIED** | On Android 14+ Health Connect is the `com.android.healthfitness` APEX module; `apps.healthdata` is the Android 13 backport and is expected to be absent |

### What the reviewer got right, and it mattered

* **D1–D4 and the D3 severity upgrade are all correct.** The D3 upgrade especially: the
  original report rated it MEDIUM, and demonstrating that a `GET` could drive a write makes
  HIGH the better call.
* **The testability recommendation was the most valuable single output of the review.** It
  proposed extracting a `HealthDataSink` interface and installing a throwing fake so the
  service's failure handling becomes reachable from JUnit. That is exactly what `v1.2.3`
  does, and it is why D1 can never regress silently now: a test throws `SecurityException`
  from the sink and asserts the `403`, the dashboard update and the step-delta retention.
* **The recommendation to decouple the display state from the write** was correct and is
  implemented, with one deliberate refinement: the reviewer suggested publishing state before
  the write and keeping the *baseline* after it. That is exactly the split adopted — display
  first, write, then commit the baseline only on success.

### Where the reviewer reasoned from stale or assumed code

The D5 claim describes code that does not exist in `v1.2.2`:

> "Notice that `hrSampleBuffer.clear()` happens **before** `flushHeartRateSamples(copy)` is
> called!"

The shipped code, unchanged since `v1.2.1`, is:

```kotlin
// Take a snapshot but do NOT clear it yet. `flushHeartRateSamples`
// returns 0 without throwing when Health Connect is unavailable, so
// draining first silently discarded every sample of the interval, once
// per 15 s, forever. Samples are removed only as they are confirmed.
val snapshot = synchronized(hrSampleBuffer) { hrSampleBuffer.toList() }
...
val count = healthManager.flushHeartRateSamples(snapshot)
if (count > 0) {
    synchronized(hrSampleBuffer) { hrSampleBuffer.removeAll(snapshot) }
}
```

So the defect was real *once*, was found and fixed in the previous round, and the review
re-reported it against a version that no longer contained it. This is the failure mode to
watch for when a reviewer works from a summary rather than from the repository.

The D0 claim is the more instructive one, because the reviewer supplied a confident
framework-level causal chain ("the APEX controller refuses to trust the rationale activity,
filters out all 18 requested permissions, and renders the sheet with 0 permissions") and also
*rejected* an environment explanation. Both turned out to be wrong, and a single two-minute
experiment settled it: reinstall the unmodified APK, open the sheet in portrait, observe that
all permissions are listed, tap "Allow all", observe all 18 granted. A plausible mechanism is
not evidence.

### Process conclusion

Three independent sources were wrong about §7 in three different ways — this report (missing
device package), the reviewer (missing manifest attribute), and the reviewer's rejection of
the environment explanation (the actual cause). The device was right. The lesson is not
"trust the device over reviewers"; it is that any claim about runtime behaviour should be
cheap to test, and should be tested, before it is written into a fix directive.

---

## 13. `v1.2.3` verification on the same device

All fixes were installed and re-tested on the OnePlus 10T before release. Health Connect
permissions were reset by uninstalling, so both the failure and success paths could be
exercised.

| Check | Expected | Observed |
|---|---|---|
| `POST /telemetry`, HC permission **denied** | `403`, dashboard still updates | `HTTP 403 {"status":"error","code":"HEALTH_PERMISSION_DENIED","hcTotal":0}` and `/status` → `bpm:72` |
| `POST /telemetry`, HC permission **granted** | `200`, records written | `HTTP 200 {"status":"synced","hcTotal":8,"hcWritten":8}` |
| Malformed JSON | `400` | `HTTP 400 {"status":"bad_request","code":400,…}` |
| Unknown path | `404` | `HTTP 404 {"status":"not_found","code":404,…}` |
| `GET /telemetry` | `405` + `Allow` | `HTTP 405 {"status":"method_not_allowed","code":405,"allow":"POST, OPTIONS"}` |
| `POST /status` | `405` | `HTTP 405 … "allow":"GET, OPTIONS"` |
| Unknown control command | `400` rejected | `HTTP 400 {"status":"rejected","code":400,"action":"Ignored Unknown Command: nonsense"}` |
| Valid control command | `200` executed | `HTTP 200 {"status":"executed","action":"Triggered Find My Pixel 9 Alarm (5s)"}` |
| `{"heartRate":290}` | not displayed | displayed value stayed at 72 |
| In-app log wording | honest attribution | `Health Connect permission denied — grant it on this device: …`, `Webhook rejected: body is not a JSON object (9 bytes)` |

Test suite: **125 tests** (was 115), Android Lint 0 errors, both CI jobs green, release
`v1.2.3` published.

