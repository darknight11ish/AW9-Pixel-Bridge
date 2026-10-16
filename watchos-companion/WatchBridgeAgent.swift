import SwiftUI
import HealthKit
import WatchKit

@main
struct WatchBridgeAgentApp: App {
    var body: some Scene {
        WindowGroup {
            WatchBridgeView()
        }
    }
}

/// Streams Apple Watch Series 9 biometrics to the Pixel 9 bridge.
///
/// Transport note: watchOS does not expose the CoreBluetooth *peripheral* role —
/// `CBPeripheralManager`, `CBMutableService` and `CBMutableCharacteristic` are all
/// marked unavailable for watchOS — so the watch cannot advertise the standard
/// Heart Rate service (0x180D / 0x2A37) as an earlier draft of this file attempted.
/// Biometrics therefore travel over the HealthKit -> HTTP webhook path on port 8484,
/// which is the same path the Android side already ingests. The Android BLE GATT
/// client remains useful for standard third-party Heart Rate peripherals.
final class WatchTelemetryStreamer: NSObject, ObservableObject, HKWorkoutSessionDelegate, HKLiveWorkoutBuilderDelegate {
    private let healthStore = HKHealthStore()
    private var workoutSession: HKWorkoutSession?
    private var builder: HKLiveWorkoutBuilder?

    @Published var pixelIp: String = "192.168.1.100"
    /// Optional shared secret. Left empty the bridge accepts any private-LAN peer,
    /// so this stays backwards compatible with an unconfigured phone.
    @Published var bridgeToken: String = ""
    @Published var liveBpm: Int = 0
    @Published var steps: Double = 0
    @Published var activeKcal: Double = 0
    @Published var distanceM: Double = 0
    @Published var spo2: Double = -1.0
    @Published var floors: Double = 0
    @Published var hrvMs: Double = 0
    /// Latest resting heart rate sampled from HealthKit. 0 means "no reading yet",
    /// which the Android side's 30...150 range check skips rather than fabricating.
    @Published var restingHr: Int = 0
    /// Latest respiratory rate in breaths/min. -1.0 means "no reading yet" and is
    /// likewise skipped by the Android side's 4...60 range check.
    @Published var respiratoryRate: Double = -1.0
    @Published var isStreaming = false
    @Published var lastPushStatus: String = "Idle"

    override init() {
        super.init()
        WKInterfaceDevice.current().isBatteryMonitoringEnabled = true
    }

    func startStreaming() {
        // Guard against a second tap: it used to create a second HKWorkoutSession while
        // the first was still active, dropping the only strong reference to the first
        // (the delegates are weak) and leaving a session that could never be stopped.
        guard !isStreaming else {
            lastPushStatus = "Already streaming"
            return
        }

        let typesToShare: Set = [HKObjectType.workoutType()]
        let typesToRead: Set = [
            HKObjectType.quantityType(forIdentifier: .heartRate)!,
            HKObjectType.quantityType(forIdentifier: .stepCount)!,
            HKObjectType.quantityType(forIdentifier: .activeEnergyBurned)!,
            HKObjectType.quantityType(forIdentifier: .distanceWalkingRunning)!,
            HKObjectType.quantityType(forIdentifier: .oxygenSaturation)!,
            HKObjectType.quantityType(forIdentifier: .flightsClimbed)!,
            HKObjectType.quantityType(forIdentifier: .heartRateVariabilitySDNN)!,
            // These two are not delivered by HKLiveWorkoutDataSource: they are
            // periodic HealthKit values, so they are polled once streaming starts.
            HKObjectType.quantityType(forIdentifier: .restingHeartRate)!,
            HKObjectType.quantityType(forIdentifier: .respiratoryRate)!
        ]

        healthStore.requestAuthorization(toShare: typesToShare, read: typesToRead) { success, _ in
            guard success else {
                DispatchQueue.main.async {
                    self.lastPushStatus = "HealthKit authorisation refused"
                }
                return
            }
            let config = HKWorkoutConfiguration()
            config.activityType = .other
            config.locationType = .indoor

            do {
                self.workoutSession = try HKWorkoutSession(healthStore: self.healthStore, configuration: config)
                self.builder = self.workoutSession?.associatedWorkoutBuilder()
                self.builder?.dataSource = HKLiveWorkoutDataSource(healthStore: self.healthStore, workoutConfiguration: config)
                self.workoutSession?.delegate = self
                self.builder?.delegate = self
                self.workoutSession?.startActivity(with: Date())
                // Only advertise streaming once collection actually began; the previous
                // version discarded this result and claimed success unconditionally.
                self.builder?.beginCollection(withStart: Date()) { began, error in
                    DispatchQueue.main.async {
                        if began {
                            self.isStreaming = true
                            self.lastPushStatus = "Streaming"
                        } else {
                            self.isStreaming = false
                            self.lastPushStatus =
                                "Collection failed: \(error?.localizedDescription ?? "unknown")"
                        }
                    }
                    if began {
                        // Fetch the latest polled vitals now, so the first webhook
                        // already carries resting HR and respiratory rate instead of
                        // waiting for the next sample to arrive.
                        self.pollBackgroundVitals()
                    }
                }
            } catch {
                DispatchQueue.main.async {
                    self.lastPushStatus = "Workout session failed: \(error.localizedDescription)"
                }
            }
        }
    }

    /// Ends the workout session cleanly.
    ///
    /// Without this the session ran until the system killed it and no workout was ever
    /// saved, despite `NSHealthUpdateUsageDescription` promising that the app starts
    /// **and saves** a workout. The Start button now also offers Stop.
    func stopStreaming() {
        guard isStreaming else { return }
        let end = Date()
        workoutSession?.stopActivity(with: end)
        builder?.endCollection(withEnd: end) { _, _ in
            self.builder?.finishWorkout { _, error in
                DispatchQueue.main.async {
                    self.isStreaming = false
                    self.lastPushStatus = error == nil
                        ? "Stopped - workout saved"
                        : "Stopped - workout save failed"
                }
            }
        }
    }
    /// Reads the most recent resting heart rate and respiratory rate from HealthKit.
    ///
    /// Neither is produced by `HKLiveWorkoutDataSource` — they are periodic values the
    /// system computes — so each is fetched with a single-sample `HKSampleQuery`
    /// sorted newest-first. Both lookups are dispatched together and the resulting
    /// payload is pushed **once**, so a partially-populated POST cannot be mistaken for
    /// a reading that does not exist.
    ///
    /// If neither type yields a sample there is nothing new to send, so nothing is sent
    /// and the reason is surfaced in the UI. The defaults (`restingHr = 0`,
    /// `respiratoryRate = -1.0`) are exactly the sentinels the Android side's range
    /// checks skip, so even a pushed payload cannot fabricate a record.
    func pollBackgroundVitals() {
        let group = DispatchGroup()
        var latestRestingHr = 0
        var latestRespiratory = -1.0
        // A plain Bool rather than a counter: both sample handlers run on HealthKit's
        // own concurrent queues, and `+= 1` from two threads is a data race (a hard
        // error under Swift 6 strict concurrency). Writing the same `true` from both is
        // idempotent, so no lock is needed.
        var anyFailure = false

        if let restingType = HKObjectType.quantityType(forIdentifier: .restingHeartRate) {
            group.enter()
            latestSample(of: restingType) { sample, failed in
                if failed { anyFailure = true }
                if let sample = sample {
                    let bpmUnit = HKUnit.count().unitDivided(by: .minute())
                    latestRestingHr = Int(sample.quantity.doubleValue(for: bpmUnit).rounded())
                }
                group.leave()
            }
        }

        if let respiratoryType = HKObjectType.quantityType(forIdentifier: .respiratoryRate) {
            group.enter()
            latestSample(of: respiratoryType) { sample, failed in
                if failed { anyFailure = true }
                if let sample = sample {
                    let breathUnit = HKUnit.count().unitDivided(by: .minute())
                    latestRespiratory = sample.quantity.doubleValue(for: breathUnit)
                }
                group.leave()
            }
        }

        group.notify(queue: .main) {
            if latestRestingHr > 0 { self.restingHr = latestRestingHr }
            if latestRespiratory > 0 { self.respiratoryRate = latestRespiratory }

            if anyFailure {
                // A HealthKit *error* is reportable. A denied read permission is NOT:
                // HealthKit deliberately reports it as "no samples and no error", so it
                // is indistinguishable from a metric the watch has simply not recorded.
                self.lastPushStatus = "HealthKit query failed"
                return
            }

            guard latestRestingHr > 0 || latestRespiratory > 0 else {
                self.lastPushStatus = "No resting HR / respiratory data yet"
                return
            }

            self.postTelemetryToPixel(controlCommand: "")
        }
    }

    /// Newest-first, limit-1 sample lookup.
    ///
    /// `completion` is always invoked exactly once so the caller's dispatch group cannot
    /// deadlock on an error path. `failed` distinguishes "the query could not run" (a
    /// permission or HealthKit problem worth reporting) from "the query ran and there is
    /// no sample", which is a normal state for a metric the watch has not recorded yet.
    private func latestSample(
        of type: HKQuantityType,
        completion: @escaping (HKQuantitySample?, Bool) -> Void
    ) {
        let sort = NSSortDescriptor(key: HKSampleSortIdentifierEndDate, ascending: false)
        let query = HKSampleQuery(sampleType: type, predicate: nil, limit: 1, sortDescriptors: [sort]) { _, samples, error in
            if let error = error {
                print("\(type.identifier) query failed: \(error)")
                completion(nil, true)
                return
            }
            completion(samples?.first as? HKQuantitySample, false)
        }
        healthStore.execute(query)
    }

    /// Resolves the step count from the start of today to now.
    ///
    /// The phone's contract for `steps` is a monotonic cumulative counter for the day
    /// (it computes `delta = value - baseline` and keeps the baseline when the value
    /// decreases). A per-workout sum cannot satisfy that, so this queries HealthKit for
    /// the day total and only falls back to the workout figure when the query fails.
    private func refreshDayStepTotal() {
        guard let stepType = HKObjectType.quantityType(forIdentifier: .stepCount) else { return }
        let startOfDay = Calendar.current.startOfDay(for: Date())
        let predicate = HKQuery.predicateForSamples(withStart: startOfDay, end: Date(), options: .strictStartDate)
        let query = HKStatisticsQuery(
            quantityType: stepType,
            quantitySamplePredicate: predicate,
            options: .cumulativeSum
        ) { _, statistics, _ in
            guard let total = statistics?.sumQuantity()?.doubleValue(for: .count()) else { return }
            DispatchQueue.main.async { self.steps = total }
        }
        healthStore.execute(query)
    }

    func workoutBuilder(_ workoutBuilder: HKLiveWorkoutBuilder, didCollectDataOf collectedTypes: Set<HKSampleType>) {
        for type in collectedTypes {
            guard let quantityType = type as? HKQuantityType,
                  let stats = workoutBuilder.statistics(for: quantityType) else { continue }

            DispatchQueue.main.async {
                switch quantityType.identifier {
                case HKQuantityTypeIdentifier.heartRate.rawValue:
                    let bpmUnit = HKUnit.count().unitDivided(by: .minute())
                    self.liveBpm = Int(stats.mostRecentQuantity()?.doubleValue(for: bpmUnit) ?? 0)
                case HKQuantityTypeIdentifier.stepCount.rawValue:
                    // `workoutBuilder.statistics` covers only the CURRENT workout, so its
                    // value restarts at 0 for every session. The phone treats `steps` as
                    // a monotonic daily counter and ignores decreases, so sending a
                    // per-workout count meant every step after a restart was discarded
                    // until the new session passed the old total. Ask HealthKit for the
                    // day total instead.
                    self.refreshDayStepTotal()
                case HKQuantityTypeIdentifier.activeEnergyBurned.rawValue:
                    self.activeKcal = stats.sumQuantity()?.doubleValue(for: .kilocalorie()) ?? self.activeKcal
                case HKQuantityTypeIdentifier.distanceWalkingRunning.rawValue:
                    self.distanceM = stats.sumQuantity()?.doubleValue(for: .meter()) ?? self.distanceM
                case HKQuantityTypeIdentifier.oxygenSaturation.rawValue:
                    // HKUnit.percent() renders 98.5% as 0.985, so normalise to 0-100.
                    // If no reading exists we keep -1.0, which makes the Android side's
                    // 70.0..100.0 range check skip the record entirely rather than
                    // writing a fabricated OxygenSaturationRecord.
                    if let q = stats.mostRecentQuantity() {
                        let pct = q.doubleValue(for: .percent())
                        self.spo2 = pct <= 1.0 ? pct * 100.0 : pct
                    }
                case HKQuantityTypeIdentifier.flightsClimbed.rawValue:
                    self.floors = stats.sumQuantity()?.doubleValue(for: .count()) ?? self.floors
                case HKQuantityTypeIdentifier.heartRateVariabilitySDNN.rawValue:
                    // The watch reports **SDNN**, which is NOT the same metric as the
                    // RMSSD that Health Connect's only HRV record type stores. This is
                    // therefore carried for on-watch display only and is deliberately
                    // NOT sent in the telemetry payload: writing it would create a
                    // mislabelled clinical record (D-05).
                    let msUnit = HKUnit.secondUnit(with: .milli)
                    self.hrvMs = stats.mostRecentQuantity()?.doubleValue(for: msUnit) ?? self.hrvMs
                default: break
                }
                self.postTelemetryToPixel(controlCommand: "")
            }
        }
    }

    /// Sends a control verb to the phone's `/control` endpoint.
    ///
    /// Commands used to be POSTed to `/telemetry`, which executes the command *and then*
    /// runs the whole ingest: every button press re-committed the last known heart rate,
    /// distance, calories and floors as fresh records at the current time, and perturbed
    /// the step baseline. `/control` does nothing but execute the command.
    func sendControlCommand(_ command: String) {
        guard let url = URL(string: "http://\(pixelIp):8484/control") else { return }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        applyBridgeToken(to: &req)
        // The phone reads `command` (WatchBridgeService /control handler).
        req.httpBody = try? JSONSerialization.data(withJSONObject: ["command": command])
        URLSession.shared.dataTask(with: req) { _, response, error in
            let status: String
            if error != nil {
                status = "Command failed"
            } else if let http = response as? HTTPURLResponse {
                switch http.statusCode {
                case 200: status = "Command sent: \(command)"
                case 401: status = "Rejected: bad bridge token"
                case 403: status = "Rejected: peer not on local network"
                default: status = "HTTP \(http.statusCode)"
                }
            } else {
                status = "No response"
            }
            DispatchQueue.main.async { self.lastPushStatus = status }
        }.resume()
    }

    private func applyBridgeToken(to req: inout URLRequest) {
        let trimmedToken = bridgeToken.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmedToken.isEmpty {
            req.setValue(trimmedToken, forHTTPHeaderField: "X-Bridge-Token")
        }
    }

    func postTelemetryToPixel(controlCommand: String) {
        guard let url = URL(string: "http://\(pixelIp):8484/telemetry") else { return }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        applyBridgeToken(to: &req)

        // `batteryLevel` is -1.0 when no reading is available. Clamping that to 0
        // published a fabricated "0 %" watch battery; the phone skips any value outside
        // 0...100, so the sentinel is preserved instead.
        let rawLevel = WKInterfaceDevice.current().batteryLevel
        let battery = rawLevel < 0 ? -1 : Int(min(1.0, rawLevel) * 100)

        var payload: [String: Any] = [
            "heartRate": liveBpm,
            "steps": Int(steps),
            "activeCalories": activeKcal,
            "distanceMeters": distanceM,
            "spo2": spo2,
            "floorsClimbed": floors,
            // NOTE: `hrvMs` (SDNN) is deliberately omitted — see the SDNN case in
            // workoutBuilder(didCollectDataOf:). Only `restingHeartRate` and
            // `respiratoryRate` are added below.
            "restingHeartRate": restingHr,
            "respiratoryRate": respiratoryRate,
            "battery": battery
        ]
        if !controlCommand.isEmpty {
            payload["controlCommand"] = controlCommand
        }
        req.httpBody = try? JSONSerialization.data(withJSONObject: payload)
        URLSession.shared.dataTask(with: req) { _, response, error in
            let status: String
            if error != nil {
                status = "Push failed"
            } else if let http = response as? HTTPURLResponse {
                switch http.statusCode {
                case 200: status = "Synced"
                case 401: status = "Rejected: bad bridge token"
                case 403: status = "Rejected: peer not on local network"
                case 413: status = "Rejected: payload too large"
                case 503: status = "Rejected: phone busy, retry"
                default: status = "HTTP \(http.statusCode)"
                }
            } else {
                status = "No response"
            }
            DispatchQueue.main.async { self.lastPushStatus = status }
        }.resume()
    }

    func workoutBuilderDidCollectEvent(_ workoutBuilder: HKLiveWorkoutBuilder) {}

    func workoutSession(
        _ workoutSession: HKWorkoutSession,
        didChangeTo toState: HKWorkoutSessionState,
        from fromState: HKWorkoutSessionState,
        date: Date
    ) {
        // The session ending means no further samples will arrive, so reflect that
        // in the UI instead of leaving the "Streaming Active" state latched.
        if toState == .ended {
            DispatchQueue.main.async { self.isStreaming = false }
        }
    }

    func workoutSession(_ workoutSession: HKWorkoutSession, didFailWithError error: Error) {
        print("HKWorkoutSession failed: \(error)")
        DispatchQueue.main.async { self.isStreaming = false }
    }
}

struct WatchBridgeView: View {
    @StateObject private var streamer = WatchTelemetryStreamer()

    var body: some View {
        ScrollView {
            VStack(spacing: 8) {
                TextField("Pixel 9 IP", text: $streamer.pixelIp)
                // A secret, so it is masked rather than rendered as plain text.
                SecureField("Bridge token (optional)", text: $streamer.bridgeToken)
                Text("\(streamer.liveBpm) BPM")
                    .font(.system(size: 26, weight: .bold))
                    .foregroundColor(.red)
                Text("Resting \(restingHrLabel(streamer.restingHr)) · Resp \(respiratoryLabel(streamer.respiratoryRate))")
                    .font(.system(size: 11))
                    .foregroundColor(.secondary)
                Text(streamer.lastPushStatus)
                    .font(.system(size: 11))
                    .foregroundColor(.secondary)
                Button(streamer.isStreaming ? "Stop Streaming" : "Start Bridge") {
                    if streamer.isStreaming {
                        streamer.stopStreaming()
                    } else {
                        streamer.startStreaming()
                    }
                }.tint(streamer.isStreaming ? .red : .green)

                // Commands go to /control, which only executes them: posting them to
                // /telemetry also re-committed the last telemetry values as new records.
                HStack {
                    Button("⏯") { streamer.sendControlCommand("MEDIA_PLAY_PAUSE") }
                    Button("⏭") { streamer.sendControlCommand("MEDIA_NEXT") }
                    Button("📸") { streamer.sendControlCommand("CAMERA_SHUTTER") }
                }
                Button("Ring Pixel 9") {
                    streamer.sendControlCommand("FIND_MY_PIXEL")
                }.tint(.orange)
                Button("Refresh Resting HR / Resp Rate") {
                    streamer.pollBackgroundVitals()
                }.tint(.blue)
            }
        }
    }

    /// Renders the sentinel as "none yet" instead of as a clinical-looking 0 BPM.
    ///
    /// These two values are not continuously available on watchOS — they are periodic
    /// HealthKit readings — so the watch commonly has no sample at all. Showing "0"
    /// would read as a measurement of zero rather than as an absence.
    private func restingHrLabel(_ value: Int) -> String {
        value > 0 ? "\(value) BPM" : "none yet"
    }

    private func respiratoryLabel(_ value: Double) -> String {
        value > 0 ? String(format: "%.1f brpm", value) : "none yet"
    }
}
