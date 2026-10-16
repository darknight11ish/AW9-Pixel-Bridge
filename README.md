# AW9-Pixel-Bridge (Apple Watch Series 9 ⇄ Google Pixel 9 Health Connect Bridge)

A sideloadable Android 14+ application (Kotlin & Jetpack Compose) plus a standalone
watchOS companion agent that forwards Apple Watch Series 9 biometrics and proximity
into **Google Health Connect** on a Google Pixel 9, entirely over your own local
network. No cloud service, account or developer program is involved in the data path.

---

## 1. What actually works (verified architecture)

The design that was originally specified — the watch advertising the standard
Bluetooth Heart Rate service — **cannot work and has been removed**. watchOS does not
expose the CoreBluetooth *peripheral* role (`CBPeripheralManager`, `CBMutableService`
and `CBMutableCharacteristic` are all marked unavailable), so an Apple Watch can never
serve `0x180D` to the phone. This was proven by compiling the Swift against the real
watchOS SDK in CI, not by reading it.

Three ingestion paths therefore exist, and only the first two can carry Apple Watch
data:

| Path | Carries | Status |
|---|---|---|
| **Wi-Fi / Pixel 9 2.4 GHz Hotspot HTTP webhook (`:8484`)** | Anything the watch can read from HealthKit and POST | **The Apple Watch path** |
| **BLE GATT (`0x180D` / `0x1814` / `0x180F`)** | Standard third-party BLE straps (chest straps, RSC sensors) | Works, but **never for an Apple Watch** |
| **Apple Health `export.xml` streaming importer** | Historical HealthKit data | Works, no watch required |

The BLE client now connects **only** when a peripheral actually advertises `0x180D` or
`0x1814`, and the dashboard reports "connected" only after those services are
discovered — so an Apple Watch in range is no longer shown as a connected sensor that
delivers nothing.

### Webhook endpoints

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/telemetry` | Ingest a telemetry payload (see §3) |
| `POST` | `/control` | `{"command":"MEDIA_PLAY_PAUSE"\|"MEDIA_NEXT"\|"CAMERA_SHUTTER"\|"FIND_MY_PIXEL"}` |
| `GET` | `/status` | `{"status":"online","bleConnected":…,"bpm":…,"hcSynced":…}` |
| `OPTIONS` | any | CORS preflight, answered without a token |

Only loopback and RFC1918 (private LAN) peers are served. An optional shared secret
(`X-Bridge-Token`, also accepted as a `authToken` JSON field) can be set from the
dashboard's **Webhook Security** card.

---

## 2. Three ways to connect an Apple Watch

### Option 1 — Apple Watch Shortcut (no Xcode, no developer account)

1. On the phone, open the app and note the **webhook endpoint** shown, e.g.
   `Hotspot (ap0): http://192.168.43.1:8484/telemetry`.
2. Put the watch on the same network (for hotspot mode, join the Pixel's 2.4 GHz
   hotspot on the watch).
3. In the watch's **Shortcuts** app, build: *Get Health Sample* (e.g. Heart Rate,
   most recent) → *Get Contents of URL* (`POST`, JSON body).
4. Since the Shortcut posts while the watch is on the move, run it manually or from a
   watch-face complication. This path is manual rather than continuous, which is the
   tradeoff for needing no Apple tooling at all.

The Shortcut may send HealthKit's own snake_case names; the Android side maps them
(§3), so no field renaming is needed.

### Option 2 — *Health Auto Export* (App Store app, paid)

*Health Auto Export* runs on the iPhone/Watch, reads Apple Health, and can push to a
REST endpoint on a schedule — natively producing the nested
`{"data":{"metrics":[…]}}` JSON that the Android side now parses (§3). In the app,
set the REST API URL to the endpoint from the dashboard and the export format to
JSON. This is the fastest route to genuinely continuous data.

### Option 3 — `WatchBridgeAgent.xcodeproj` (needs Xcode + an Apple account)

The watchOS companion is a real app source tree with a spec, but it is **not
installable until you generate and sign it on a Mac**:

```bash
brew install xcodegen          # NOT pre-installed on GitHub's macOS runner images
cd watchos-companion
xcodegen generate              # produces WatchBridgeAgent.xcodeproj from project.yml
open WatchBridgeAgent.xcodeproj
```

Then set your team/bundle ID in Xcode and deploy to the watch. On-watch installation
is the most restricted path Apple offers and the app expires periodically, so a paid
account is realistically required. Once installed, the app streams live heart rate,
HRV, energy, distance, steps, floors, SpO₂ and battery, and polls resting heart rate
and respiratory rate.

---

## 3. Accepted `/telemetry` JSON shapes

The universal parser accepts all three dialects, and scales only where the producer
omits the unit:

**1. Native flat camelCase** (this project's own agent):

```json
{"heartRate":74,"steps":9120,"distanceMeters":7100.5,"activeCalories":420.2,
 "spo2":97.5,"floorsClimbed":12,"hrvMs":41.5,"restingHeartRate":52,
 "respiratoryRate":14.5,"sleepMinutes":455,"battery":84,
 "controlCommand":"FIND_MY_PIXEL"}
```

`findMyPixel: true` is still accepted as a legacy alias for `"FIND_MY_PIXEL"`.

**2. Apple Shortcuts snake_case / HealthKit aliases** — `heart_rate`/`bpm`,
`step_count`, `active_energy`/`active_calories`, `walking_running_distance` (**km** →
metres) or `distance_km`, `blood_oxygen_saturation`/`oxygen_saturation`
(a `0.5–1.0` fraction is scaled to percent), `flights_climbed`,
`heart_rate_variability`/`hrv_sdnn`, `resting_heart_rate`, `respiratory_rate`,
`sleep_minutes`, `battery_level` (a `0.0–1.0` fraction is scaled to 0–100).

**3. *Health Auto Export* nested metrics** — `{"data":{"metrics":[{"name":…,"units":…,
"data":[{"qty":…}]}]}}`. `qty` is used, falling back to `Avg` for heart rate;
incremental series (steps, energy, floors, distance) are **summed**, point-in-time
metrics take the latest sample, and `units: "km"` is converted to metres.

Malformed or empty bodies return an all-defaults payload whose negative sentinels
downstream range checks skip, so nothing fabricated is ever written to Health Connect.

---

## 4. Pixel 9 2.4 GHz Hotspot mode (recommended)

Running the phone's own hotspot is more reliable than a shared router: the watch joins
as an ordinary 2.4 GHz client (the Apple Watch has **no 5 GHz radio**), and the phone is
the DHCP server, so no router configuration or client isolation can interfere.

1. Enable **Settings → Network → Hotspot & tethering → Wi-Fi hotspot** on the Pixel 9.
2. On the watch: **Settings → Wi-Fi → join the hotspot SSID** (enter the password on
   the watch — there is no Android equivalent of Apple's Instant Hotspot).
3. Open the bridge app. The webhook card lists every usable interface, **hotspot
   first**, e.g.:

   ```text
   Hotspot (ap0): http://192.168.43.1:8484/telemetry
   Wi-Fi (wlan0): http://192.168.1.25:8484/telemetry
   ```

   Addresses are re-resolved automatically when Wi-Fi or the hotspot toggles, and
   **Refresh IP** forces it manually. Use the `192.168.43.1` entry from the watch —
   a plain `192.168.x.x` router address is not reachable while the watch is on the
   hotspot.
4. Optional hardening: in **Webhook Security**, save a shared token. The watch side
   (Shortcut header, Health Auto Export header, or the companion app's token field)
   must then send the same value as `X-Bridge-Token`.

Battery note: hotspot + HTTP server + BLE scanning together drain the phone noticeably.

---

## 5. Supported Google Health Connect records

`HeartRateRecord`, `HeartRateVariabilityRmssdRecord`, `StepsRecord`, `DistanceRecord`,
`ActiveCaloriesBurnedRecord`, `OxygenSaturationRecord`, `FloorsClimbedRecord`,
`RestingHeartRateRecord`, `RespiratoryRateRecord`, `SleepSessionRecord`.

Apple Health's HRV metric is **SDNN**, which is not interchangeable with Health
Connect's only HRV type (RMSSD). SDNN import is therefore gated off by default and
must be opted into explicitly.

---

## 6. Quick webhook test via `curl`

```bash
curl -X POST http://<PIXEL_9_IP>:8484/telemetry \
  -H "Content-Type: application/json" \
  -H "X-Bridge-Token: your-token-if-set" \
  -d '{"heartRate":76,"steps":4250,"activeCalories":310.5,"spo2":98.5,"floorsClimbed":6,"hrvMs":44.2,"battery":84,"controlCommand":"FIND_MY_PIXEL"}'
```

A Shortcuts-style body works just as well:

```bash
curl -X POST http://<PIXEL_9_IP>:8484/telemetry \
  -H "Content-Type: application/json" \
  -d '{"heart_rate":68,"walking_running_distance":2.4,"blood_oxygen_saturation":0.985}'
```

---

## 7. Build and verify

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease --parallel
```

Toolchain: Gradle 8.11.1, AGP 8.9.1, `compileSdk 36`, `minSdk 34`, `targetSdk 35`,
Kotlin 1.9.24, Compose Compiler 1.5.14, Compose BOM 2024.09.00,
`androidx.health.connect:connect-client:1.1.0`, JDK 17.

CI runs two jobs on every push: `build-and-test` (Ubuntu, JDK 17 — tests, lint, both
APKs, and a GitHub Release on a `v*` tag) and `watchos-companion-typecheck`
(macOS 14 — installs XcodeGen, generates the Xcode project, builds it, and type-checks
the Swift against the real watchOS SDK).

Reproducible repository metrics (file counts, byte totals, per-file SHA-256) come from:

```bash
python3 scripts/verify_repo_metrics.py            # Markdown table
python3 scripts/verify_repo_metrics.py --tree     # per-directory totals
python3 scripts/verify_repo_metrics.py --rev v1.0.2 --json
```

---

## 8. What still needs real hardware

The APK has been compile-, unit-test- and type-check-verified only; it has never run
on a physical Pixel 9. Unverified without a device: real GATT behaviour, the hour-
aligned step windows and millisecond record IDs against a live Health Connect
provider, `0x004C` Continuity crosstalk, and the hotspot/webhook round trip from an
actual Apple Watch.
