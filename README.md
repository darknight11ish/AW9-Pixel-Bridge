# AW9-Pixel-Bridge (Apple Watch Series 9 ⇄ Google Pixel 9 Health Connect Bridge)

A sideloadable Android 14/15+ application (Kotlin & Jetpack Compose) and standalone watchOS companion agent that bridges Apple Watch Series 9 biometrics, proximity radar, and hardware remote controls directly into **Google Health Connect** on the Google Pixel 9.

## Tri-Modal Ingestion Architecture

1. **Passive Apple Continuity Sniffer (`0x004C` BLE):**
   - Decodes Apple Nearby Info (`0x10`) and Nearby Action (`0x0F`) BLE frames.
   - Provides real-time RSSI distance estimation and On-Wrist / Unlocked status detection with zero watch configuration.
2. **Active BLE GATT Biometric Client (`0x180D`, `0x1814`, `0x180F`):**
   - Connects to standard Bluetooth Heart Rate (`0x2A37`), Running Speed & Cadence (`0x2A53`), and Battery (`0x2A19`) services.
   - Extracts live BPM, Wrist Sensor Contact bit, and `1/1024s` RR-Intervals to compute live **HRV (RMSSD)** in milliseconds.
3. **Local Wi-Fi / Hotspot Webhook Server (Port `8484`) & XML Importer:**
   - Exposes `POST /telemetry`, `POST /control`, and `GET /status` on port `8484`.
   - Includes a streaming `XmlPullParser` engine to import historical Apple Health `export.xml` archives into Google Health Connect.

## Supported Google Health Connect Records
- `HeartRateRecord`
- `HeartRateVariabilityRmssdRecord`
- `StepsRecord`
- `DistanceRecord`
- `ActiveCaloriesBurnedRecord`
- `OxygenSaturationRecord` ($SpO_2$)
- `FloorsClimbedRecord`
- `RestingHeartRateRecord`
- `RespiratoryRateRecord`
- `SleepSessionRecord`

## Quick Webhook Test via `curl`
```bash
curl -X POST http://<PIXEL_9_IP>:8484/telemetry \
  -H "Content-Type: application/json" \
  -d '{"heartRate":76,"steps":4250,"activeCalories":310.5,"spo2":98.5,"floorsClimbed":6,"hrvMs":44.2,"battery":84,"controlCommand":"FIND_MY_PIXEL"}'
```
