import AVFoundation
import Foundation
import Speech

@MainActor
final class SpeechInput: ObservableObject {
    @Published private(set) var transcript = ""
    @Published private(set) var recording = false
    @Published private(set) var error: String?

    private let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "zh-CN"))
    private let audioEngine = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var hasTap = false

    func start() {
        guard !recording else { return }
        transcript = ""
        error = nil
        SFSpeechRecognizer.requestAuthorization { [weak self] status in
            Task { @MainActor in
                guard status == .authorized else {
                    self?.error = "需要语音识别权限"
                    return
                }
                AVAudioSession.sharedInstance().requestRecordPermission { granted in
                    Task { @MainActor in
                        guard granted else {
                            self?.error = "需要麦克风权限"
                            return
                        }
                        self?.begin()
                    }
                }
            }
        }
    }

    func stop() {
        guard recording else { return }
        recording = false
        audioEngine.stop()
        if hasTap {
            audioEngine.inputNode.removeTap(onBus: 0)
            hasTap = false
        }
        request?.endAudio()
    }

    func cancel() {
        stop()
        task?.cancel()
        cleanup()
        transcript = ""
    }

    private func begin() {
        guard let recognizer, recognizer.isAvailable else {
            error = "当前设备暂不支持语音识别"
            return
        }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.record, mode: .measurement, options: .duckOthers)
            try session.setActive(true, options: .notifyOthersOnDeactivation)

            let request = SFSpeechAudioBufferRecognitionRequest()
            request.shouldReportPartialResults = true
            self.request = request

            let input = audioEngine.inputNode
            let format = input.outputFormat(forBus: 0)
            guard format.sampleRate > 0 else {
                error = "麦克风不可用"
                deactivateSession()
                return
            }
            input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak request] buffer, _ in
                request?.append(buffer)
            }
            hasTap = true

            audioEngine.prepare()
            try audioEngine.start()
            recording = true

            task = recognizer.recognitionTask(with: request) { [weak self] result, taskError in
                Task { @MainActor in
                    guard let self else { return }
                    if let result {
                        self.transcript = result.bestTranscription.formattedString
                        if result.isFinal { self.finish() }
                    } else if taskError != nil {
                        self.finish()
                        if self.transcript.isEmpty {
                            self.error = "没听清，再试一次"
                        }
                    }
                }
            }
        } catch {
            self.error = "无法启动录音"
            finish()
        }
    }

    private func finish() {
        if recording {
            audioEngine.stop()
            recording = false
        }
        if hasTap {
            audioEngine.inputNode.removeTap(onBus: 0)
            hasTap = false
        }
        request?.endAudio()
        task = nil
        request = nil
        deactivateSession()
    }

    private func cleanup() {
        if hasTap {
            audioEngine.inputNode.removeTap(onBus: 0)
            hasTap = false
        }
        request = nil
        task = nil
    }

    private func deactivateSession() {
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}
