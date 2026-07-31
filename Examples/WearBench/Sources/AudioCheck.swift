/*
 * AudioCheck — mic route/level check and a speaker test tone.
 *
 * Answers one key question: "is audio coming in through the glasses mic right now, or the phone?"
 * (Glasses audio is standard BT HFP — the silent fallback is a regular in the failure-mode encyclopedia)
 */

import AVFAudio
import Foundation
import WearScope

@MainActor
final class AudioCheck: ObservableObject {
  @Published private(set) var routeInfo = "-"
  @Published private(set) var report: String?
  @Published private(set) var running = false

  private var engine: AVAudioEngine?

  func refreshRoute() {
    let route = AVAudioSession.sharedInstance().currentRoute
    let fmt: ([AVAudioSessionPortDescription]) -> String = { ports in
      ports.isEmpty ? "none"
        : ports.map { "\($0.portType.rawValue)(\($0.portName))" }.joined(separator: ", ")
    }
    routeInfo = "in: \(fmt(route.inputs))\nout: \(fmt(route.outputs))"
  }

  /// 2-second recording: reports the actual input port, sample rate, and level (dBFS).
  func runMicCheck() async {
    guard !running else { return }
    running = true
    defer { running = false }
    report = nil

    let session = AVAudioSession.sharedInstance()
    do {
      try session.setCategory(.playAndRecord, mode: .default,
                              options: [.allowBluetooth, .defaultToSpeaker])
      if let hfp = session.availableInputs?.first(where: { $0.portType == .bluetoothHFP }) {
        try? session.setPreferredInput(hfp)  // prefer the glasses
      }
      try session.setActive(true)
    } catch {
      report = "Audio session failed: \(error.localizedDescription)"
      return
    }

    guard await AVAudioApplication.requestRecordPermission() else {
      report = "Microphone permission denied"
      return
    }

    // Guard against the 0 Hz issue (failure-mode encyclopedia) — recreate the engine every check
    let engine = AVAudioEngine()
    self.engine = engine
    let input = engine.inputNode
    let format = input.inputFormat(forBus: 0)
    guard format.sampleRate > 0 else {
      report = "⚠️ Input format 0 Hz — known failure mode (engine must be recreated). Try again."
      WearScope.trackError("input format 0Hz", context: "bench.mic")
      return
    }

    let power = PowerMeter()
    input.installTap(onBus: 0, bufferSize: 2048, format: format) { buffer, _ in
      power.consume(buffer)
    }
    do {
      try engine.start()
    } catch {
      report = "Engine start failed: \(error.localizedDescription)"
      return
    }
    try? await Task.sleep(nanoseconds: 2_000_000_000)
    input.removeTap(onBus: 0)
    engine.stop()

    refreshRoute()
    let inputPort = AVAudioSession.sharedInstance().currentRoute.inputs.first
    let viaGlasses = inputPort?.portType == .bluetoothHFP
    let db = power.averageDb
    report = """
    \(viaGlasses ? "✅ Glasses (HFP) mic" : "⚠️ Phone mic — fell back from glasses") \
    · \(Int(format.sampleRate))Hz · avg \(String(format: "%.0f", db))dBFS\(db < -50 ? " (near-silent — check input)" : "")
    """
    WearScope.track(.audioRoute, "bench_mic", [
      "via_glasses": "\(viaGlasses)", "sample_rate": "\(Int(format.sampleRate))",
      "avg_dbfs": String(format: "%.0f", db),
    ])
  }

  /// 0.6-second test tone (440 Hz) on the current output route.
  func playTone() {
    let engine = AVAudioEngine()
    self.engine = engine
    let player = AVAudioPlayerNode()
    engine.attach(player)
    let format = engine.outputNode.outputFormat(forBus: 0)
    engine.connect(player, to: engine.outputNode, format: format)

    let frames = AVAudioFrameCount(format.sampleRate * 0.6)
    guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames) else { return }
    buffer.frameLength = frames
    for ch in 0..<Int(format.channelCount) {
      guard let data = buffer.floatChannelData?[ch] else { continue }
      for i in 0..<Int(frames) {
        data[i] = sin(2.0 * .pi * 440.0 * Float(i) / Float(format.sampleRate)) * 0.4
      }
    }
    do {
      try engine.start()
    } catch { return }
    player.scheduleBuffer(buffer) { [weak self] in
      Task { @MainActor in self?.engine?.stop() }
    }
    player.play()
    WearScope.track(.audioRoute, "bench_tone", [:])
  }
}

/// Accumulates buffer RMS (audio thread) — lock-guarded.
private final class PowerMeter: @unchecked Sendable {
  private let lock = NSLock()
  private var sumSquares: Double = 0
  private var count: Int = 0

  func consume(_ buffer: AVAudioPCMBuffer) {
    guard let data = buffer.floatChannelData?[0] else { return }
    var local: Double = 0
    for i in 0..<Int(buffer.frameLength) { local += Double(data[i] * data[i]) }
    lock.lock()
    sumSquares += local
    count += Int(buffer.frameLength)
    lock.unlock()
  }

  var averageDb: Double {
    lock.lock()
    defer { lock.unlock() }
    guard count > 0 else { return -120 }
    let rms = (sumSquares / Double(count)).squareRoot()
    return rms > 0 ? 20 * log10(rms) : -120
  }
}
