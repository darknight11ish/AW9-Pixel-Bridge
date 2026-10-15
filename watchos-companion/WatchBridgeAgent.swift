import SwiftUI
import HealthKit
import CoreBluetooth
import WatchKit

@main
struct WatchBridgeAgentApp: App {
    var body: some Scene {
        WindowGroup {
            WatchBridgeView()
        }
    }
}

final class WatchTelemetryStreamer: NSObject, ObservableObject, HKWorkoutSessionDelegate, HKLiveWorkoutBuilderDelegate, CBPeripheralManagerDelegate {
    private let healthStore = HKHealthStore()
    private var workoutSession: HKWorkoutSession?
    private var builder: HKLiveWorkoutBuilder?
    private var peripheralManager: CBPeripheralManager?
    private var hrCharacteristic: CBMutableCharacteristic?

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
    @Published var isStreaming = false

    override init() {
        super.init()
        WKInterfaceDevice.current().isBatteryMonitoringEnabled = true
        peripheralManager = CBPeripheralManager(delegate: self, queue: nil)
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
            HKObjectType.quantityType(forIdentifier: .heartRateVariabilitySDNN)!
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
            } catch {
                print("HKWorkoutSession Error: \(error)")
            }
        }
    }

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        guard peripheral.state == .poweredOn else { return }
        let serviceUUID = CBUUID(string: "180D")
        let charUUID = CBUUID(string: "2A37")
        hrCharacteristic = CBMutableCharacteristic(
            type: charUUID,
            properties: [.notify],
            value: nil,
            permissions: [.readable]
        )
        let service = CBMutableService(type: serviceUUID, primary: true)
        service.characteristics = [hrCharacteristic!]
        peripheralManager?.add(service)
        peripheralManager?.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [serviceUUID],
            CBAdvertisementDataLocalNameKey: "Apple Watch S9"
        ])
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
                    self.broadcastBleHeartRate(bpm: self.liveBpm)
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
                default: break
                }
                self.postTelemetryToPixel(controlCommand: "")
            }
        }
    }

    private func broadcastBleHeartRate(bpm: Int) {
        guard let char = hrCharacteristic, bpm > 0 else { return }
        let packet = Data([0x06, UInt8(clamping: bpm)])
        peripheralManager?.updateValue(packet, for: char, onSubscribedCentrals: nil)
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
            "battery": battery,
            "controlCommand": controlCommand
        ]
        req.httpBody = try? JSONSerialization.data(withJSONObject: payload)
        URLSession.shared.dataTask(with: req).resume()
    }

    func workoutBuilderDidCollectEvent(_ workoutBuilder: HKLiveWorkoutBuilder) {}
    func workoutSession(_ workoutSession: HKWorkoutSession, didChangeTo toState: HKWorkoutSessionState, from fromState: HKWorkoutSessionState, date: Date) {}
    func workoutSession(_ workoutSession: HKWorkoutSession, didFailWithError error: Error) {}
}

struct WatchBridgeView: View {
    @StateObject private var streamer = WatchTelemetryStreamer()

    var body: some View {
        ScrollView {
            VStack(spacing: 8) {
                TextField("Pixel 9 IP", text: $streamer.pixelIp)
                SecureField("Bridge token (optional)", text: $streamer.bridgeToken)
                Text("\(streamer.liveBpm) BPM")
                    .font(.system(size: 26, weight: .bold))
                    .foregroundColor(.red)
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
            }
        }
    }
}
