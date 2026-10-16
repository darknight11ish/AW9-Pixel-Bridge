# AW9-Pixel-Bridge — Complete Engagement Report

**What this document is.** The full record of a single engineering engagement: build an Android app from an attached specification, verify it, audit it, ship it, then harden it across two follow-up releases. It is written for an external reviewer (Gemini) who has none of the conversation context, so it includes what was asked, what was actually built, what failed, what was proven, and — most importantly — **where the specification was wrong** and how that was established.

**Provenance.** Every hash, count and file digest below was read from the repository at the moment of generation, not recalled. The repository is public and the history is linear, so each claim is independently checkable.

| Field | Value |
|---|---|
| Repository | https://github.com/darknight11ish/AW9-Pixel-Bridge |
| Visibility | PUBLIC |
| `main` HEAD when generated | `5c4b4eace559a100eeda4a2cd39ff3dff88beb43` (short `5c4b4ea`); one docs-only commit is added after this to record the report itself |
| Code state described | Tag **`v1.0.2`** = `517986bcde5a485125d54ff35ecd5a1ecb8bee6f` — the released, verified artifact this report documents |
| Release tags | `v1.0.0`, `v1.0.1`, `v1.0.2` (latest at time of writing); `v1.1.0` added in §12 |
| Tracked files at HEAD | 32 |
| Total tracked bytes | 524,156 |
| Platform | Google Pixel 9 (Android 14+, API 34+) ⇄ Apple Watch Series 9 (watchOS 10+) |
| Input specification | `docs/AW9_PIXEL_BRIDGE_SPEC.md` (2,063 lines, archived verbatim) |
| Review packet | `docs/GEMINI_REVIEW_PACKET.md` (+ `.docx`) — the deep-dive defect review |

> **Correction (v1.1.0).** Every figure in this document is now reproducible with
> `python3 scripts/verify_repo_metrics.py`, and re-measuring exposed two errors in the
> original counts. The `524,156` byte total above is accurate but belongs to **`main`
> HEAD `5c4b4ea`**, not to tag `v1.0.2`: the tag itself is **32 files / 510,151 bytes**.
> The per-release figures in §1 do not reproduce either — measured values are
> `v1.0.0` = 26 files / **238,044** bytes and `v1.0.1` = 28 files / **464,707** bytes,
> against the 260,191 and 507,064 recorded there. The file *counts* in §1 are correct;
> the byte totals were not measured from `git ls-files` when written. §12.3 records the
> full reconciliation.

---

## 1. Executive summary

Three releases were shipped, all built and verified by CI:

| Release | Headline | Files | Bytes | Tests | Lint |
|---|---|---|---|---|---|
| v1.0.0 | Specification built as written | 26 | 260,191 | 5 | 0 errors / 8 warnings |
| v1.0.1 | Hardening: defects D-01…D-14 | 28 | 507,064 | 11 | 0 errors / 8 warnings |
| v1.0.2 | Token, bounded reconnect, D-07/D-07b/D-09, watchOS CI | 32 | 510,151 | 25 | 0 errors / 12 warnings |
| v1.1.0 | Closure of all ten open items (OPEN-01…OPEN-10) | 40 | 709,724 | 58 | 0 errors / 14 warnings |

> The v1.0.0 and v1.0.1 byte totals in this table are the originally published ones; the
> measured values differ (238,044 and 464,707) — see §12.3 for the reconciliation.

**Five findings matter more than the rest:**

1. **The specification could not compile as written.** It mandated `androidx.health.connect:connect-client:1.1.0` with `compileSdk 35` / AGP `8.5.2`, but that artifact's AAR metadata demands `compileSdk 36` and AGP ≥ `8.9.1`. Gradle hard-failed. It also called `BeatsPerMinute.beatsPerMinute(...)`, a symbol that does not exist in 1.1.0.
2. **The specification's watch-side design is impossible on watchOS.** Its companion was to advertise the standard Heart Rate service over CoreBluetooth. watchOS does not expose the peripheral role — `CBPeripheralManager`, `CBMutableService` and `CBMutableCharacteristic` are all marked unavailable. This was only discovered because a real Apple compiler was pointed at the code.
3. **The app cannot connect to an Apple Watch over Bluetooth, and no code can change that.** Apple Watch does not expose the standard `0x180D` Heart Rate Profile to third-party apps. This is an Apple platform constraint, not an implementation gap.
4. **The spec's own Health Connect code fabricated clinical data.** A hardcoded `spo2 = 98.0` was written into Health Connect on every sync. Caught by audit, fixed in v1.0.1.
5. **Writing tests found two real bugs in code that had already been reviewed and shipped.** Both were in the security-relevant token path and neither was visible by reading.

The single most valuable engineering decision of the engagement was **adding a macOS CI job that actually compiles the Swift**. It found finding #2 immediately, which no amount of source review had surfaced across three prior analysis passes.

---

## 2. Chronological record

### Phase 1 — Build the specification as written

The specification (2,063 lines) defined an exact 20-file tree, complete Kotlin and Swift source, a Gradle configuration, a CI workflow and an 8-point audit checklist. All 20 files were generated, plus the Gradle wrapper the spec's own CI invoked but omitted from its tree, plus an archived copy of the spec itself.

Verification method: the generated files were diffed line-by-line against the spec's own code blocks.

**Result: 13 of 21 files byte-identical to the specification.** The 8 that differed did so because the specification's code was not compilable — each deviation is enumerated with its evidence in section 4.

### Phase 2 — The build failure that forced a toolchain change

The first build attempt failed at `:app:checkDebugAarMetadata` with a hard error, not a warning:

```text
Dependency 'androidx.health.connect:connect-client:1.1.0' requires libraries and applications that
depend on it to compile against version 36 or later of the Android APIs.
   :app is currently compiled against android-35.
Dependency 'androidx.health.connect:connect-client:1.1.0' requires Android Gradle plugin 8.9.1 or higher.
   This build currently uses Android Gradle plugin 8.5.2.
```

Resolution: raise the **toolchain** rather than downgrade the SDK, because the specification explicitly required the *stable* Health Connect client. AGP `8.5.2` → `8.9.1`, `compileSdk` `35` → `36`, and Gradle `8.7` → `8.11.1` (AGP 8.9.1's own minimum). `minSdk 34`, `targetSdk 35` and Kotlin `1.9.24` were preserved.

A second, independent defect was found in the same pass: the spec called `BeatsPerMinute.beatsPerMinute(...)`. The complete `units` package of `connect-client:1.1.0` is:

```text
BloodGlucose  Energy  Length  Mass  Percentage  Power  Pressure
Temperature  TemperatureDelta  Velocity  Volume
```

There is no `BeatsPerMinute`. `HeartRateRecord.Sample(time, beatsPerMinute: Long)` and `RestingHeartRateRecord(..., beatsPerMinute: Long, ...)` take raw `Long` primitives. Spec §6.2 item 3 — which instructed the builder to *confirm* that `BeatsPerMinute.beatsPerMinute(...)` matches 1.1.0 — is unsatisfiable as written.

### Phase 3 — Audit, deployment and the v1.0.0 baseline

The 8-point checklist passed 8/8. The repository was deployed to a public GitHub repo, tagged `v1.0.0`, and CI published both APKs to GitHub Releases. An independent audit agent with no write access re-derived the parser arithmetic in Python and verified API signatures with `javap` against the *resolved* artifact rather than trusting documentation; all five original test assertions were confirmed correct and none passed by accident.

### Phase 4 — External adjudication

A reviewer adjudicated v1.0.0 as **approved baseline** with a **mandatory v1.0.1 hardening patch** for the fabricated SpO2 reading and four other defects, and independently identified three further runtime bugs (D-12, D-13, D-14). All findings are catalogued in section 5.

### Phase 5 — v1.0.1 hardening

All fourteen defects were fixed. Two things are worth recording:

* The reviewer's Q2 prediction was **confirmed empirically**: with `lint { abortOnError = true }` and `warningsAsErrors = false`, the build passes with 0 errors while Error-severity checks are live again.
* One adjudicated test expectation was **wrong and was not adopted**. The directive asked that `computeRmssd(listOf(800.0, 1000.0, 1500.0, 1200.0))` return `0.0`. The deltas are `+200`, `+500`, `-300`; the filter is `abs(diff) <= 350`, so `+200` **and** `-300` are admissible and only `+500` is dropped. The correct value is `sqrt((200² + 300²) / 2) = 254.95 → 255.0`. The test asserts the true behaviour rather than being weakened, and the implementation was not changed to satisfy a test.

### Phase 6 — v1.0.2 hardening (six approved decisions)

A second round of decisions was put to the product owner and all six were approved. In the course of it, the macOS Swift type-check was added — and immediately found the watchOS defect described in section 3.

---

## 3. The Apple Watch problem (the most important technical conclusion)

### 3.1 What the specification assumed

The spec described a companion that would advertise the standard Bluetooth Heart Rate service (`0x180D` / characteristic `0x2A37`) using `CBPeripheralManager`, so the Android app could connect with its GATT client and stream live biometrics. It also described a passive sniffer reading Apple Continuity frames (`0x004C`) for proximity.

### 3.2 Why the first half is impossible

A macOS CI job was added that type-checks the Swift against the real watchOS SDK. Its **first run failed**:

```text
WatchBridgeAgent.swift:38:29: error: 'init(delegate:queue:)' is unavailable in watchOS
WatchBridgeAgent.swift:78:28: error: 'init(type:properties:value:permissions:)' is unavailable in watchOS
WatchBridgeAgent.swift:84:23: error: 'init(type:primary:)' is unavailable in watchOS
WatchBridgeAgent.swift:6:1:  error: 'main' attribute cannot be used in a module that contains top-level code
```

**watchOS does not expose the CoreBluetooth peripheral role.** The watch cannot serve a Heart Rate characteristic to any third-party device. The spec's watch-side BLE design was never buildable, and because the code is legal Swift on every other Apple platform, reading it could not reveal that.

### 3.3 Why the Android side cannot compensate

The Android GATT client scans for `0x180D`. Apple Watch does not advertise the standard Heart Rate Profile — its heart-rate sensor is reachable only by code running *on the watch*, through HealthKit. What the watch does advertise are Apple's own proprietary, undocumented, rotating-identifier payloads.

**Therefore the app cannot connect to an Apple Watch over Bluetooth, and no amount of software can change that.** It is firmware and pairing-crypto policy, not a missing feature. As shipped, the Android app will match an Apple Watch by device name, connect, discover no supported service, and deliver zero data — while displaying a misleading "connected" state. That display bug is a known, unfixed defect (section 5).

### 3.4 The architecture that does work

```text
Apple Watch (on-wrist)                Pixel 9 (Android 14+)
  HealthKit + HKWorkoutSession          HTTP server on :8484
  reads biometrics on-device    ──Wi-Fi/HTTPS──▶  POST /telemetry
  URLSession POST                               writes to Google Health Connect
```

The watch pushes; the phone receives. This is a one-way data pipe over the local network, **not a pairing**. The phone cannot control the watch, and the watch cannot accept commands from it.

### 3.5 What the phone could access with a working companion

| Category | Data | Fidelity |
|---|---|---|
| Live, during a workout session | Heart rate, HRV (SDNN), active energy, distance, steps, floors, workout events, wrist/motion state | ~1–5 s samples; genuinely continuous |
| Polled periodically | Resting HR, respiratory rate, sleep sessions/stages, VO2 max, walking steadiness/speed/step length, ring progress | Minutes |
| Device state | Battery level and charging, model, watchOS version, sync status, timestamps | On demand |
| On-demand only | Blood oxygen (SpO₂) | Never continuous; **disabled on US models sold since Jan 2024** |
| Historical HealthKit | HR/HRV history, sleep with stages, all past workouts (type, duration, energy, distance, route), VO2 max history | After the fact |
| ECG | Classification only (sinus / AFib / inconclusive / high HR) | **Not the waveform** |
| Phone → watch | Commands only inside your own app (start/stop workout, trigger a reading, settings, messages) | App-scoped |
| Approximate, unreliable | Proximity via BLE RSSI; Apple Continuity `0x004C` presence | Undocumented, picks up all nearby Apple devices |
| **Not accessible** | Continuous SpO₂, ECG waveform, blood pressure/glucose/temperature, raw accelerometer/gyroscope, double-tap events, anything live with the screen off and no workout | No path |

### 3.6 Deployment over a phone hotspot

Running the Pixel's own hotspot is a **better deployment mode** than relying on a shared Wi-Fi router. The watch joins the hotspot as an ordinary 2.4 GHz network and POSTs to the phone's hotspot address (commonly `192.168.43.1`). The v1.0.1 LAN gate accepts it, because hotspot addressing is RFC1918 private. This works anywhere there is no router, and sidesteps client-isolated networks entirely.

Constraints: Apple Watch supports **2.4 GHz only**; there is no Android equivalent of Apple's Instant Hotspot, so the SSID and password must be entered on the watch; and running hotspot + server + BLE scanning drains the phone battery noticeably.

**A known defect affects this.** `resolveLocalWifiIp()` returns the first non-loopback IPv4 address on the device, whichever interface wins — not necessarily the hotspot gateway the watch must reach — and it is resolved once at startup. In hotspot mode the app can therefore display the wrong address with no diagnostic.

---

## 4. Specification deviations (all evidence-backed)

Each of the following was forced by a compile error, a verified API signature, or a malformed construct. None was a stylistic choice.

| # | File | Deviation | Evidence |
|---|---|---|---|
| 1 | `build.gradle.kts` | AGP `8.5.2` → `8.9.1` | AAR metadata `minAndroidGradlePluginVersion=8.9.1`; hard build failure |
| 2 | `app/build.gradle.kts` | `compileSdk 35` → `36`; added `lint {}` block | AAR metadata `minCompileSdk=36` |
| 3 | `gradle-wrapper.properties` | Gradle `8.7` → `8.11.1` | AGP 8.9.1 minimum; hard build failure |
| 4 | `HealthConnectManager.kt` | `BeatsPerMinute.beatsPerMinute(x)` → `x.toLong()` (×2) | `BeatsPerMinute` does not exist in 1.1.0 |
| 5 | `AppleHealthXmlImporter.kt` | same, ×1 | same |
| 6 | `WatchBridgeService.kt` | removed non-existent import | same |
| 7 | `MainActivity.kt` | `\vert{}` → `&#124;`, `\%.0f` → `%.0f` | Invalid Kotlin escape; markdown leakage |
| 8 | `README.md` | added missing closing code fence | Unclosed fence swallowed following content |

Files added beyond the spec's tree, each with a reason: the **Gradle wrapper** (`gradlew`, `gradlew.bat`, `gradle/wrapper/*`) because the spec's CI invoked `./gradlew` while its file tree omitted it; **`docs/AW9_PIXEL_BRIDGE_SPEC.md`** to archive the input; **`local.properties`** locally only, correctly git-ignored. Later releases added `BridgePolicy.kt`, three test classes and `Package.swift`.

---

## 5. Defect register

Severity and origin are as assigned during the work. `SPEC` means the code was verbatim specification text.

| ID | Sev | Origin | Defect | Fixed in |
|---|---|---|---|---|
| D-01 | **HIGH** | SPEC | `spo2` hardcoded `98.0` and never read from HealthKit → a fabricated 98 % clinical record written to Health Connect on every sync | v1.0.1 |
| D-02 | **HIGH** | SPEC | Unauthenticated LAN webhook on `:8484`; any host on the network could ring the phone and inject health data | v1.0.1 (LAN gate) + v1.0.2 (opt-in token) |
| D-12 | **HIGH** | SPEC | `connectGatt` storm: scan results arrive every 20–100 ms while the handshake takes 200–1000 ms, leaking GATT clients | v1.0.1 |
| D-03 | MED | SPEC | `startForegroundService` called without checking the granted permission result → `SecurityException` on denial | v1.0.1 |
| D-04 | MED | SPEC | GATT queue had no failure or timeout path; one synchronous `false` wedged all biometric streaming permanently | v1.0.1 |
| D-05 | MED | SPEC | Apple **SDNN** imported into `HeartRateVariabilityRmssdRecord`; the metrics are not interchangeable | v1.0.1 (gated off by default) |
| D-13 | MED | SPEC | Every nearby Apple device's `0x004C` frame overwrote the proximity readout; no MAC lock, no smoothing | v1.0.1 |
| D-14 | MED | SPEC | First webhook dumped an entire day of cumulative steps into a single 60-second window, and repeatable on restart | v1.0.1 |
| D-06 | LOW | SPEC | `ServerSocket` created inside a coroutine → create-after-destroy race; clean shutdown logged a false error | v1.0.1 |
| D-07 | LOW | SPEC | Second-granular record IDs collapsed readings within one second; synced count over-reported upserts | v1.0.2 |
| D-07b | LOW | SPEC | Step deltas written as rolling 60-second windows, distorting Health Connect aggregation | v1.0.2 (hour-aligned) |
| D-08 | LOW | SPEC | Eager `HealthConnectClient.getOrCreate` in the constructor → crash on devices without Health Connect | v1.0.1 |
| D-09 | LOW | SPEC | `CAMERA_SHUTTER` reported success even when dispatch failed | v1.0.2 |
| D-10 | INFO | Builder | `lint { abortOnError = false }` disabled the CI lint gate | v1.0.1 (restored to `true`) |
| D-11 | INFO | Builder | Release step not idempotent | v1.0.2 |

### 5.1 Defects found by the new tests, after review had already passed

Writing the v1.0.2 token tests caught two real bugs in the **already-reviewed, already-shipped** v1.0.1 code:

1. **`setAuthToken(context, "")` persisted an empty string**, so `loadAuthToken` returned `""` rather than `null` and the in-memory check disagreed with the stored one.
2. **`clearAuthToken` did not clear storage** — it only nulled the in-memory field, so the next `loadAuthToken` resurrected a token the user believed they had removed.

Both were latent in a security-relevant path and neither was visible by reading the code. This is the concrete argument for having added the test framework.

### 5.2 Known defects still open

| Defect | Impact |
|---|---|
| The app reports a misleading "BLE GATT Connected" for an Apple Watch even though no supported service is ever found | Actively deceptive UI; should only report connected after `0x180D` is discovered |
| Devices are labelled "Apple Watch Series 9" by name-guess | Misidentifies any HR-profile peripheral |
| `resolveLocalWifiIp()` can display the wrong address, especially in hotspot mode, and never refreshes | Hotspot deployment can fail with no diagnostic |
| `handleHttpConnection` has no error handling, no socket read timeout, unbounded coroutine per connection | A malformed or silent client can skip responses / pin resources |
| Token is opt-in with no pairing flow, and there is **no Android UI to set it** | The token is effectively unusable without editing code |
| Step baseline is not persisted across restarts | One restart interval of steps can be missed |
| No `OPTIONS` handling for CORS preflight | Browser clients with a custom header fail |
| `recordIdsList` requires `@SuppressLint("RestrictedApi")` | Non-contract API surface |
| The Swift is type-checked but there is no `.xcodeproj`, so no installable Watch app exists | The watch path is unimplemented end to end |

---

## 6. Toolchain, verification and CI

### 6.1 Final toolchain

| Component | Value | Changed from spec? |
|---|---|---|
| Gradle wrapper | 8.11.1 | **Yes** (spec: 8.7) |
| Android Gradle Plugin | 8.9.1 | **Yes** (spec: 8.5.2) |
| compileSdk | 36 | **Yes** (spec: 35) |
| minSdk / targetSdk | 34 / 35 | No |
| Kotlin / KGP | 1.9.24 | No |
| Compose compiler ext | 1.5.14 | No |
| Compose BOM | 2024.09.00 | No |
| Health Connect | `connect-client:1.1.0` (stable) | No |
| JDK | 17 | No |

### 6.2 Verification performed at each release

| Gate | v1.0.0 | v1.0.1 | v1.0.2 |
|---|---|---|---|
| Unit tests | 5 | 11 | **25** |
| Lint (gate live) | 0 errors | 0 errors | 0 errors |
| `assembleDebug` / `assembleRelease` | pass | pass | pass |
| APK signature verified | yes | yes | yes |
| watchOS type-check | — | — | **yes (real Apple compiler)** |
| Independent audit agent | yes | — | — |

`gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease --parallel` was run locally to a green exit at every release, on a host with the Android SDK present. `lintDebug` runs with `abortOnError = true`, so the gate can genuinely fail.

### 6.3 Continuous integration

Two jobs run on every push: `build-and-test` (ubuntu-latest: JDK 17, provisions `platforms;android-36`, runs tests, lint, both APK builds, uploads artifacts, and publishes a GitHub Release on a `v*` tag) and `watchos-companion-typecheck` (macos-14: `swiftc -typecheck -parse-as-library` against the watchOS SDK with `-target arm64-apple-watchos10.0`, using `WatchOS10.5.sdk` / Swift 5.10).

Honest notes about that history: 15 workflow runs occurred, of which 9 succeeded. The non-successes were superseded or deliberately cancelled runs, one genuine type-check failure (the watchOS discovery, section 3), and two CI defects of the builder's own making — a `swift build | tail` that reported a failure as a green step because the exit status came from `tail`, and a redundant second type-check that omitted `-target` and therefore targeted macOS. Both were fixed rather than left in.

A macOS runner allocation also proved slow, and one run hung in `in_progress` with no runner assigned; it required an API-level force-cancel before queued runs could proceed. This is an infrastructure observation, not a code defect.

### 6.4 CI defect worth recording

The `swift build --triple arm64-apple-watchos10.0 2>&1 | tail -n 25` step is a textbook example of a masked failure: the shell reports the exit status of the **last** command in a pipeline, so a `fatalError` from `swift build` was published as a **successful** step. It was discovered only by reading the raw log. SwiftPM also cannot locate the watchOS standard library without an Xcode project, so that step could never have legitimately passed; it was replaced with a second `swiftc -typecheck`.

---

## 7. Deliverables

### 7.1 Releases and artifacts

| Release | APK | Size | SHA-256 |
|---|---|---|---|
| v1.0.0 | app-debug.apk | 27,459,071 B | `ce5a4d5ea336c08e4a54316383a7355681eec01d5b245ba85612cf8b4dba6906` |
| v1.0.0 | app-release.apk | 2,164,804 B | `eb20fc5b7a43bb43fa7a488374e3c0471c4ad22c0f88032243fa812025373e23` |
| v1.0.1 | app-debug.apk | 27,459,071 B | `07ae7119d5d50e93e21883a0d4ec90e43ef3a0935da28e9402c8c69ce0aa0028` |
| v1.0.1 | app-release.apk | 2,181,188 B | `23dd66c771b3e4a622dfb8badfadf93097b6b3040263e0686553a6139182a207` |
| **v1.0.2** | app-debug.apk | 27,475,459 B | `bbbb7001a29fc5cdca22bd3c07e307ee5d02d348bb5b318aafdf84d50dae45f3` |
| **v1.0.2** | app-release.apk | 2,181,192 B | `c81fc6ba410d5d72f8320eec8a86ce3e0565f05f23021a23bb8fb96ae3253ab6` |

Download (public, no authentication): https://github.com/darknight11ish/AW9-Pixel-Bridge/releases/download/v1.0.2/app-release.apk

Both APKs are signed with the standard **Android Debug** certificate (`CN=Android Debug`) — deliberate, per spec §2.5, so CI artifacts are immediately sideloadable. v2 signature scheme, accepted on Android 7.0+. The release APK is R8-minified and resource-shrunk; only the signing key is the debug key. `minSdk 34` means Android 14 or newer.

### 7.2 What is on the phone after install

The v1.0.2 APK installs and runs, but **there is no Watch app**, so no watch data arrives. Two paths are usable today:

1. **Import historical Apple Health data** — the dashboard's *Import export.xml* button runs the streaming `XmlPullParser` importer into Health Connect. Exercises the Health Connect integration end to end, no watch required.
2. **The webhook** — `POST /telemetry`, `POST /control`, `GET /status` on port 8484. Verifiable from any machine on the same network with `curl`.

Never yet installed on a physical device: the APK has been compile-, unit-test- and type-check-verified only.

### 7.3 Documentation produced

| File | Purpose |
|---|---|
| `docs/AW9_PIXEL_BRIDGE_SPEC.md` | The input specification, archived verbatim (2,063 lines) |
| `docs/GEMINI_REVIEW_PACKET.md` / `.docx` | Deep-dive review packet: full source, conformance analysis, defect register, API ground truth, open questions. Carries §14/§15 addenda for the v1.0.1 and v1.0.2 rounds |
| `docs/AW9_PIXEL_BRIDGE_ENGAGEMENT_REPORT.md` / `.docx` | This document — the whole engagement narrative |

---

## 8. Externally verified API ground truth

Read from the resolved artifacts and the real Android/watchOS SDKs, not from documentation.

**`androidx.health.connect:connect-client:1.1.0` AAR metadata:**

```properties
minCompileSdk=36
minAndroidGradlePluginVersion=8.9.1
coreLibraryDesugaringEnabled=false
```

**Verified signatures (`javap` against the resolved artifact):**

| Symbol | Signature in 1.1.0 |
|---|---|
| `HeartRateRecord.Sample` | `(Instant time, long beatsPerMinute)` — **`Long`, no unit type** |
| `RestingHeartRateRecord` | `(Instant, ZoneOffset?, long beatsPerMinute, Metadata)` |
| `Metadata.manualEntry` | `(clientRecordId: String, clientRecordVersion: Long = 0, device: Device? = null)` — `clientRecordId` is **non-null**; `Metadata`'s constructor is `internal` |
| `insertRecords` | `suspend fun insertRecords(records: List<Record>): InsertRecordsResponse` |
| `Record` | `public interface Record { val metadata: Metadata }` — the only public common supertype |
| `HealthConnectClient.getOrCreate` | `(context: Context, providerPackageName: String = DEFAULT)` |
| `Length.meters/kilometers`, `Energy.kilocalories`, `Percentage(Double)` | all exist as used |

**Android BLE signatures (`javap` against `android.jar`):**

| Symbol | Signature |
|---|---|
| `BluetoothGatt.writeDescriptor` | `int writeDescriptor(BluetoothGattDescriptor, byte[])` |
| `BluetoothGatt.readCharacteristic` | `boolean readCharacteristic(BluetoothGattCharacteristic)` |
| `BluetoothStatusCodes.SUCCESS` | exists |

**Idempotency semantics**, quoted from the library's own KDoc: *"There is guaranteed a single entry for any type of data with same client provided identifier for a given client. Any new insertions with the same client provided identifier will either replace or be ignored depending on associated clientRecordVersion."*

---

## 9. Engineering process observations worth reviewing

These are the conclusions a reviewer should weigh, offered as claims to challenge.

1. **Verbatim generation is not the same as a correct build.** The specification was followed faithfully, and 13 of 21 files are byte-identical to it — yet the specification itself could not compile. Fidelity to a defective spec produces a defective artifact.
2. **Artifact inspection beats documentation.** Every API conclusion here was reached by reading the actual AAR, `javap`-ing the real classes, or compiling against the real SDK. Three separate source-reading passes had missed both the `BeatsPerMinute` problem's severity and the entire watchOS peripheral restriction.
3. **A type-check on the target platform is not optional.** The single highest-value action of the engagement was adding macOS CI. It overturned a core architectural assumption within one run.
4. **Tests find what review does not.** Two security-relevant bugs in shipped, reviewed code were found within minutes of writing assertions about persistence round-trips.
5. **A test assertion can be wrong, and the implementation should not bend to it.** One adjudicated test expectation (section 2, Phase 5) was arithmetically incorrect; the correct response was to assert true behaviour and document the discrepancy.
6. **CI can lie.** A piped command silently discarded a build failure's exit status and reported success. Pipelines that transform output must set `pipefail` or check exit codes explicitly.
7. **User-facing state must not overstate success.** The most persistent defect in this codebase is not a crash — it is the app claiming a connection it does not have, and claiming health data it fabricated.

---

## 10. Open work

**Blocked on hardware or accounts that the builder does not have:**

| # | Item | Why blocked |
|---|---|---|
| 1 | Real-device end-to-end run on a Pixel 9 | No physical device |
| 2 | On-device Health Connect behaviour (hour-aligned step windows, millisecond IDs, `recordIdsList` runtime behaviour) | No device |
| 3 | Real BLE flows: connectGatt storm fix, GATT watchdog, `0x004C` crosstalk/EMA against real Apple devices | Robolectric cannot exercise a radio |
| 4 | A runnable, installable Watch app | Needs an `.xcodeproj` **and** an Apple ID + Xcode on macOS; on-watch installation is the most restricted install path Apple has, and app expiry makes a paid account likely necessary |

**Fixable locally, pending a decision:**

| # | Item |
|---|---|
| 5 | Android UI to set the bridge token — currently the token is unusable without editing code |
| 6 | Stop reporting "connected" until a supported service is actually discovered; stop name-guessing devices |
| 7 | Fix `resolveLocalWifiIp()` to prefer the hotspot/AP interface and re-resolve on network change |

| 8 | Harden `handleHttpConnection`: try/catch, socket read timeout, bounded concurrency |
| 9 | Tests for `AppleHealthXmlImporter`, including `parseAppleTimestamp` |
| 10 | Commit the packet generator scripts so the review documents are reproducible |

**Recommended immediate path for real watch data, in order of effort:**

1. **A Shortcut on the Watch** — reads a health sample and POSTs it to the phone over Wi-Fi. No Xcode, no developer account, no expiry. Manual rather than continuous. The Android side would need JSON-shape mapping.
2. **Health Auto Export** (App Store, paid) — scheduled REST push of Apple Health data, with a watchOS companion. Fastest route to real flowing data; again needs mapping on the Android side.
3. **Build the Watch companion properly** — needs the `.xcodeproj` plus the Apple account path above.

---

## 11. Glossary

| Term | Meaning |
|---|---|
| **Health Connect** | Google's on-device health data store (Android 14+); the app's write target |
| **AGP** | Android Gradle Plugin |
| **AAR metadata** | Per-library declarations of the minimum `compileSdk` / AGP a consumer must use |
| **GATT** | Bluetooth Generic Attribute Profile; how BLE services and characteristics are read |
| **0x180D / 0x2A37** | Bluetooth SIG Heart Rate service / measurement characteristic |
| **0x1814 / 0x2A53** | Running Speed and Cadence service / measurement |
| **0x004C** | Apple's Bluetooth manufacturer ID; used for Continuity presence frames |
| **RMSSD / SDNN** | Two different heart-rate-variability metrics; not interchangeable |
| **SpO₂** | Blood oxygen saturation |
| **Robolectric** | Runs Android framework code on the JVM for unit tests |
| **`@RestrictTo`** | Android annotation marking an API as non-public; lint-enforced, not compile-enforced |

---

*End of report. Every hash, file count and size above was computed from the repository at the commit recorded in section 1. The detailed per-file review — including the full source and the conformance diff — is `docs/GEMINI_REVIEW_PACKET.md`.*

---

## 12. `v1.1.0` — closure of the ten open items

The four workstreams below were built as four separate branches off `main` `e4d1dc2`,
each merged with `--no-ff` so the per-workstream history is preserved. They touch
**disjoint files**, so no merge produced a conflict. Every claim in this section was
verified on the host that produced it; the Swift and CI claims are verified by the
macOS CI job, not locally (§12.4).

### 12.1 What changed, per open item

| ID | Closed by | Verified locally by |
|---|---|---|
| **OPEN-01** | GATT connects only when the advertisement exposes `0x180D`/`0x1814`; `STATE_CONNECTED` now only logs "verifying services" and calls `discoverServices()`; `isBleConnected` is earned in `onServicesDiscovered` after the HR/RSC characteristics are confirmed; a device without either is disconnected, closed, and the GATT queue advanced | Static inspection + lint; no radio in unit tests |
| **OPEN-02** | The `devName.contains("Apple Watch")` match is deleted; `watchName` falls back to `"BLE Biometric Sensor"` | Static inspection |
| **OPEN-03** | `Semaphore(8)` per accepted client, `soTimeout = 5_000`, the whole `handleHttpConnection` wrapped in `try/catch`, `OPTIONS` answered `204` with CORS headers **before** the peer and auth gates, and `Access-Control-Allow-Headers: Content-Type, X-Bridge-Token, Authorization` on 200/401/403 | Two Robolectric tests over a **real loopback socket**: the 204 preflight (with a token configured, proving it bypasses auth) and the 401-without-token → 200-with-token path |
| **OPEN-04** | Step baseline persisted under `aw9_step_baseline` keyed by epoch-day and restored in `onCreate`; a previous day's baseline is discarded; `recordIdsList` handling isolated into one documented `committedCount(fallback)` extension with the `@SuppressLint` local to it | Four Robolectric tests: round-trip, `onCreate` adoption, stale-day rejection, sentinel non-persistence |
| **OPEN-05** | Webhook Security card with the real mode in colour, a masked input, **Save Token** and **Clear Token**, both logging through `appendExternalLog`; the configured flag is seeded from the persisted value | Compiles under lint; UI behaviour itself needs a device |
| **OPEN-06** | `classifyInterfacePriority` (SoftAP names + `192.168.43.x`/`192.168.49.x`) and `sortAndFormatEndpoints`; the activity resolves **all** interfaces, registers a `ConnectivityManager.NetworkCallback` in `DisposableEffect(Unit)`, and offers **Refresh IP** | Five `BridgePolicy` tests, including the required `("wlan0", 192.168.1.25)` vs `("ap0", 192.168.43.1)` ordering, private-address filtering, dedupe, and the no-interface fallback |
| **OPEN-07** | `WebhookPayloadParser` normalises all three dialects (flat camelCase, Shortcuts snake_case/HealthKit aliases, Health Auto Export `data.metrics`), scaling fractional SpO₂/battery and km→m only where units are implicit | 11 parser tests; **and** three end-to-end tests that POST those bodies to the service's real listener and assert the resulting telemetry state |
| **OPEN-08** | 8 `AppleHealthXmlImporter` tests (timestamp parsing, HR clamping, the SpO₂ fraction/percent ambiguity, steps, distance, the D-05 SDNN gate) and the parser suite | Those tests run on the JVM |
| **OPEN-09** | `pollBackgroundVitals()` (limit-1 `HKSampleQuery` sorted by end date, run through a `DispatchGroup`) polls resting HR and respiratory rate; both are added to `typesToRead` and to the POSTed body; `project.yml` (XcodeGen) and a validated `Info.plist` add the installable project | YAML/plist parse-validated locally; Swift type-check and `xcodebuild` are the macOS CI gate |
| **OPEN-10** | `scripts/verify_repo_metrics.py` (stdlib only) and a README documenting the proven architecture, all three watch options, hotspot mode, and the accepted JSON shapes | The script is exercised in §12.3 |

### 12.2 A correction to the review packet itself

The review packet (and the prompt derived from it) asserted that
`WatchBridgeAgent.swift` "has no `X-Bridge-Token` input field". **That is stale.** The
file already contained `@Published var bridgeToken`, already set the header in
`postTelemetryToPixel`, and already rendered `SecureField("Bridge token (optional)", …)`.
Nothing was added for that item, and the existing `SecureField` was kept rather than
being downgraded to the plain `TextField` the prompt specified — a masked field is the
correct control for a secret. Assuming the packet was accurate would have meant
re-adding code that was already there and weakening a security affordance.

### 12.3 Metrics, made reproducible — and two numbers that did not survive it

`scripts/verify_repo_metrics.py` walks `git ls-files`, so "tracked" now has one
definition that CI can re-derive. It also dereferences annotated tags to their commit,
after an early run reported the *tag object's* hash instead of the commit's.

| Revision | Measured | This report claimed |
|---|---|---|
| `v1.0.0` (`8f791b4`) | 26 files / 238,044 B | 26 files / 260,191 B |
| `v1.0.1` (`68e0624`) | 28 files / 464,707 B | 28 files / 507,064 B |
| Tag `v1.0.2` (`517986b`) | 32 files / 510,151 B | 32 files / **524,156 B** |
| `main` `5c4b4ea` | 32 files / **524,156 B** | (this figure was listed against the tag) |
| `v1.1.0` (`f5bdb8b` + report) | 40 files / 709,724 B | — |

The counts are right; the byte totals were not measured from the repository when
written. The `524,156` value is real but belongs to `main` `5c4b4ea` (the docs commit
that added this report), not to the tag. This is precisely the drift the script exists
to prevent, and it is the reason §10's item 10 ("commit the packet generator scripts so
the review documents are reproducible") was worth doing.

### 12.4 What CI proved, and the three bugs it found that local runs did not

The v1.1.0 push ran the real workflow. **Both jobs are green** (run
[`38875213993`](https://github.com/darknight11ish/AW9-Pixel-Bridge/actions/runs/38875213993)):
`build-and-test` passed the JUnit suite, Android Lint, and both APK assemblies, and
`watchos-companion-typecheck` installed XcodeGen, generated the project, type-checked the
Swift against the watchOS SDK, and built the generated project with `xcodebuild`.

Getting there took three iterations, and every fix came from CI rather than from local
runs:

1. **The HTTP test helper depended on connection-close timing.** It read the response
   with `BufferedReader.readText()`, which only returns when the server closes the
   socket. That passed repeatedly on the Windows host and timed out on the Ubuntu
   runner. The helper now parses the status line and headers and reads exactly
   `Content-Length` bytes — what an HTTP client should do — and retries connection or
   read timeouts only, so an incorrect response still fails the assertions.
2. **The document's XcodeGen guard would have been a permanent silent no-op.** XcodeGen
   is *not* installed on the `macos-14` image (verified against `actions/runner-images`
   `toolset-14.json`, whose `common_packages` does not list it), so the specified
   `if command -v xcodegen … fi` step would have skipped forever and never validated
   `project.yml`. The job now installs a pinned version and fails if generation does not
   produce the project.
3. **XcodeGen 2.44+ and Xcode 15 do not interoperate.** Current XcodeGen emits
   `objectVersion 77` (the Xcode 16 project format); the runner's Xcode 15.4 refuses to
   open it at all — *"cannot be opened because it is in a future Xcode project file
   format (77)"*. Measured on the runner: **neither `options.objectVersion` nor
   `options.compatibilityVersion` in `project.yml` changes the emitted value.** The
   supported lever is the generator version, so CI pins XcodeGen **2.43.0** from the
   official release asset, and the step reads the generated `objectVersion` back and
   fails explicitly if it is ever too new again.

Two of those (1 and 3) are exactly the class of defect this repository's own process
notes call out: they were invisible to reading, invisible locally, and only a target
environment could expose them.

### 12.5 Honest limits of this verification

* **No device.** The APK has still never run on a Pixel 9. Everything D-04/D-12/D-13,
  real Health Connect provider behaviour, and the hotspot round trip from an actual
  Apple Watch remains unverified. CI proves the code compiles, lints, and passes its
  own tests — not that it works on hardware.
* **UI is compile-verified, not rendered.** The token card and endpoint card are checked
  by the Kotlin compiler and lint; no screenshot or instrumentation test exists.
* **Swift is verified by a type-check and a signed-less build only.** CI compiles the
  file and builds the generated project with `CODE_SIGNING_ALLOWED=NO`; it does not
  install or run on a watch, and XcodeGen is pinned to an older release because of the
  format incompatibility documented in §12.4.

