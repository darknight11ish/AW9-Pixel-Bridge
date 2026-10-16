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
            guard success else { return }
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
                self.builder?.beginCollection(withStart: Date()) { _, _ in }
                DispatchQueue.main.async { self.isStreaming = true }
                // Fetch the latest polled vitals now, so the first webhook already
                // carries resting HR and respiratory rate instead of waiting for the
                // next sample to arrive.
                self.pollBackgroundVitals()
            } catch {
                print("HKWorkoutSession Error: \(error)")
            }
        }
    }

    /// Reads the most recent resting heart rate and respiratory rate from HealthKit.
    ///
    /// Neither is produced by `HKLiveWorkoutDataSource` — they are periodic values the
    /// system computes — so each is fetched with a single-sample `HKSampleQuery`
    /// sorted newest-first. Both lookups are dispatched together and the resulting
    /// payload is pushed once, so a partially-populated POST cannot be mistaken for a
    /// reading that does not exist.
    func pollBackgroundVitals() {
        let group = DispatchGroup()
        var latestRestingHr = 0
        var latestRespiratory = -1.0

        if let restingType = HKObjectType.quantityType(forIdentifier: .restingHeartRate) {
            group.enter()
            latestSample(of: restingType) { sample in
                if let sample = sample {
                    let bpmUnit = HKUnit.count().unitDivided(by: .minute())
                    latestRestingHr = Int(sample.quantity.doubleValue(for: bpmUnit).rounded())
                }
                group.leave()
            }
        }

        if let respiratoryType = HKObjectType.quantityType(forIdentifier: .respiratoryRate) {
            group.enter()
            latestSample(of: respiratoryType) { sample in
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
            self.postTelemetryToPixel(controlCommand: "")
        }
    }

    /// Newest-first, limit-1 sample lookup. Completion is always invoked exactly once so
    /// the caller's dispatch group cannot deadlock on an error path.
    private func latestSample(of type: HKQuantityType, completion: @escaping (HKQuantitySample?) -> Void) {
        let sort = NSSortDescriptor(key: HKSampleSortIdentifierEndDate, ascending: false)
        let query = HKSampleQuery(sampleType: type, predicate: nil, limit: 1, sortDescriptors: [sort]) { _, samples, error in
            if let error = error {
                print("\(type.identifier) query failed: \(error)")
            }
            completion(samples?.first as? HKQuantitySample)
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
                    self.steps = stats.sumQuantity()?.doubleValue(for: .count()) ?? self.steps
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
                    // The watch reports SDNN; the Android side only persists RMSSD and
                    // gates SDNN separately, so this is carried for display/telemetry
                    // and is not written to Health Connect as RMSSD.
                    let msUnit = HKUnit.secondUnit(with: .milli)
                    self.hrvMs = stats.mostRecentQuantity()?.doubleValue(for: msUnit) ?? self.hrvMs
                default: break
                }
                self.postTelemetryToPixel(controlCommand: "")
            }
        }
    }

    func postTelemetryToPixel(controlCommand: String) {
        guard let url = URL(string: "http://\(pixelIp):8484/telemetry") else { return }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let trimmedToken = bridgeToken.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmedToken.isEmpty {
            req.setValue(trimmedToken, forHTTPHeaderField: "X-Bridge-Token")
        }
        let battery = Int(max(0, WKInterfaceDevice.current().batteryLevel) * 100)
        let payload: [String: Any] = [
            "heartRate": liveBpm,
            "steps": Int(steps),
            "activeCalories": activeKcal,
            "distanceMeters": distanceM,
            "spo2": spo2,
            "floorsClimbed": floors,
            "hrvMs": hrvMs,
            "restingHeartRate": restingHr,
            "respiratoryRate": respiratoryRate,
            "battery": battery,
            "controlCommand": controlCommand
        ]
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
                Text("Resting \(streamer.restingHr) BPM · Resp \(String(format: "%.1f", streamer.respiratoryRate)) brpm")
                    .font(.system(size: 11))
                    .foregroundColor(.secondary)
                Text(streamer.lastPushStatus)
                    .font(.system(size: 11))
                    .foregroundColor(.secondary)
                Button(streamer.isStreaming ? "Streaming Active" : "Start Bridge") {
                    streamer.startStreaming()
                }.tint(.green)

                HStack {
                    Button("⏯") { streamer.postTelemetryToPixel(controlCommand: "MEDIA_PLAY_PAUSE") }
                    Button("⏭") { streamer.postTelemetryToPixel(controlCommand: "MEDIA_NEXT") }
                    Button("📸") { streamer.postTelemetryToPixel(controlCommand: "CAMERA_SHUTTER") }
                }
                Button("Ring Pixel 9") {
                    streamer.postTelemetryToPixel(controlCommand: "FIND_MY_PIXEL")
                }.tint(.orange)
                Button("Refresh Resting HR / Resp Rate") {
                    streamer.pollBackgroundVitals()
                }.tint(.blue)
            }
        }
    }
}
