import AVFoundation
import Photos
import SwiftUI
import UIKit

final class CameraController: NSObject, ObservableObject {
    @Published private(set) var ready = false
    @Published private(set) var recording = false
    @Published private(set) var transitioning = false
    @Published private(set) var permissionDenied = false
    @Published private(set) var status: String?

    let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "com.beyond.camera.capture", qos: .userInitiated)
    private let photos = AVCapturePhotoOutput()
    private let movies = AVCaptureMovieFileOutput()
    private var videoInput: AVCaptureDeviceInput?
    private var audioInput: AVCaptureDeviceInput?
    private var active = false
    private var pendingPhotos = Set<Int64>()
    private var photoNames = [Int64: String]()
    private var lastCentisecond: Int64 = 0
    private let photoClock: DateFormatter = {
        let clock = DateFormatter()
        clock.locale = Locale(identifier: "en_US_POSIX")
        clock.timeZone = TimeZone(secondsFromGMT: 0)
        clock.dateFormat = "yyyyMMddHHmmssSS"
        return clock
    }()

    override init() {
        super.init()
        let center = NotificationCenter.default
        center.addObserver(self, selector: #selector(interrupted(_:)), name: AVCaptureSession.wasInterruptedNotification, object: session)
        center.addObserver(self, selector: #selector(interruptionEnded(_:)), name: AVCaptureSession.interruptionEndedNotification, object: session)
        center.addObserver(self, selector: #selector(runtimeError), name: AVCaptureSession.runtimeErrorNotification, object: session)
    }

    deinit { NotificationCenter.default.removeObserver(self) }

    func activate() {
        authorize(.video) { allowed in
            self.queue.async {
                self.active = true
                guard allowed else {
                    self.message("Allow camera access in Settings.", permission: true)
                    return
                }
                self.startSession()
            }
        }
    }

    func deactivate() {
        queue.async {
            self.active = false
            if self.movies.isRecording { self.movies.stopRecording() }
            if self.session.isRunning { self.session.stopRunning() }
            DispatchQueue.main.async { self.ready = false }
        }
    }

    func switchCamera() {
        queue.async {
            guard !self.movies.isRecording, let oldInput = self.videoInput else { return }
            let position: AVCaptureDevice.Position = oldInput.device.position == .back ? .front : .back
            guard let input = self.makeInput(position) else { return }
            self.session.beginConfiguration()
            self.session.removeInput(oldInput)
            if self.session.canAddInput(input) {
                self.session.addInput(input)
                self.videoInput = input
            } else if self.session.canAddInput(oldInput) {
                self.session.addInput(oldInput)
            }
            self.session.commitConfiguration()
        }
    }

    func takePhoto() {
        let orientation = captureOrientation()
        queue.async {
            guard self.active, self.session.isRunning, !self.movies.isRecording else { return }
            if self.pendingPhotos.count >= 8 {
                self.message("Camera is processing the previous photos.")
                return
            }
            let settings = AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.jpeg])
            settings.photoQualityPrioritization = .speed
            self.configure(self.photos.connection(with: .video), orientation)
            self.pendingPhotos.insert(settings.uniqueID)
            let now = Int64(Date().timeIntervalSince1970 * 100)
            self.lastCentisecond = max(now, self.lastCentisecond + 1)
            let date = Date(timeIntervalSince1970: Double(self.lastCentisecond) / 100)
            self.photoNames[settings.uniqueID] = "BYND_IMG_\(self.photoClock.string(from: date)).jpg"
            self.photos.capturePhoto(with: settings, delegate: self)
        }
    }

    func toggleVideo() {
        guard !transitioning else { return }
        transitioning = true
        if recording {
            queue.async { self.movies.stopRecording() }
            return
        }
        let orientation = captureOrientation()
        authorize(.audio) { withAudio in
            self.queue.async {
                guard self.active, self.session.isRunning, !self.movies.isRecording else {
                    DispatchQueue.main.async { self.transitioning = false }
                    return
                }
                if withAudio { self.addAudioInput() }
                let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".mov")
                self.configure(self.movies.connection(with: .video), orientation)
                self.movies.startRecording(to: url, recordingDelegate: self)
            }
        }
    }

    private func startSession() {
        guard active else { return }
        if videoInput == nil {
            guard let input = makeInput(.back) ?? makeInput(.front) else {
                message("No camera is available on this device.")
                return
            }
            session.beginConfiguration()
            defer { session.commitConfiguration() }
            guard session.canAddInput(input) else {
                message("The camera could not start.")
                return
            }
            session.addInput(input)
            guard session.canAddOutput(photos) else {
                session.removeInput(input)
                message("Photo capture is unavailable.")
                return
            }
            session.addOutput(photos)
            guard session.canAddOutput(movies) else {
                session.removeOutput(photos)
                session.removeInput(input)
                message("Video recording is unavailable.")
                return
            }
            session.addOutput(movies)
            videoInput = input
        }
        if !session.isRunning { session.startRunning() }
        DispatchQueue.main.async {
            self.ready = true
            self.status = nil
            self.permissionDenied = false
        }
    }

    private func makeInput(_ position: AVCaptureDevice.Position) -> AVCaptureDeviceInput? {
        guard let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: position) else { return nil }
        return try? AVCaptureDeviceInput(device: camera)
    }

    private func addAudioInput() {
        guard audioInput == nil, let mic = AVCaptureDevice.default(for: .audio),
              let input = try? AVCaptureDeviceInput(device: mic) else { return }
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        if session.canAddInput(input) {
            session.addInput(input)
            audioInput = input
        }
    }

    private func configure(_ connection: AVCaptureConnection?, _ orientation: AVCaptureVideoOrientation) {
        guard let connection else { return }
        if connection.isVideoOrientationSupported { connection.videoOrientation = orientation }
        if connection.isVideoStabilizationSupported { connection.preferredVideoStabilizationMode = .auto }
    }

    private func captureOrientation() -> AVCaptureVideoOrientation {
        let scene = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }
        switch scene?.interfaceOrientation {
        case .landscapeLeft: return .landscapeLeft
        case .landscapeRight: return .landscapeRight
        case .portraitUpsideDown: return .portraitUpsideDown
        default: return .portrait
        }
    }

    private func authorize(_ media: AVMediaType, completion: @escaping (Bool) -> Void) {
        switch AVCaptureDevice.authorizationStatus(for: media) {
        case .authorized: completion(true)
        case .notDetermined: AVCaptureDevice.requestAccess(for: media, completionHandler: completion)
        default: completion(false)
        }
    }

    private func save(_ changes: @escaping () -> Void, completion: @escaping (Bool) -> Void = { _ in }) {
        PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
            guard status == .authorized || status == .limited else {
                self.message("Allow Beyond Camera to add captures to Photos.", permission: true)
                completion(false)
                return
            }
            PHPhotoLibrary.shared().performChanges(changes) { saved, _ in
                self.message(saved ? "Saved to Photos" : "Could not save to Photos.")
                completion(saved)
            }
        }
    }

    private func message(_ value: String, permission: Bool = false) {
        DispatchQueue.main.async {
            self.status = value
            self.permissionDenied = permission
        }
    }

    @objc private func interrupted(_ notification: Notification) {
        DispatchQueue.main.async {
            self.ready = false
            self.status = "Camera interrupted."
        }
    }

    @objc private func interruptionEnded(_ notification: Notification) { queue.async { self.startSession() } }

    @objc private func runtimeError(_ notification: Notification) {
        let error = notification.userInfo?[AVCaptureSessionErrorKey] as? AVError
        if error?.code == .mediaServicesWereReset {
            queue.async { self.startSession() }
        } else {
            DispatchQueue.main.async {
                self.ready = false
                self.transitioning = false
                self.status = "Camera stopped. Return to the app to retry."
            }
        }
    }
}

extension CameraController: AVCapturePhotoCaptureDelegate {
    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        guard error == nil, let data = photo.fileDataRepresentation() else {
            message("Could not capture the photo.")
            return
        }
        queue.async {
            let options = PHAssetResourceCreationOptions()
            options.originalFilename = self.photoNames[photo.resolvedSettings.uniqueID]
            self.save {
                PHAssetCreationRequest.forAsset().addResource(with: .photo, data: data, options: options)
            }
        }
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishCaptureFor resolvedSettings: AVCaptureResolvedPhotoSettings, error: Error?) {
        queue.async {
            self.pendingPhotos.remove(resolvedSettings.uniqueID)
            self.photoNames.removeValue(forKey: resolvedSettings.uniqueID)
        }
        if error != nil { message("Could not capture the photo.") }
    }
}

extension CameraController: AVCaptureFileOutputRecordingDelegate {
    func fileOutput(_ output: AVCaptureFileOutput, didStartRecordingTo fileURL: URL, from connections: [AVCaptureConnection]) {
        DispatchQueue.main.async {
            self.recording = true
            self.transitioning = false
        }
    }

    func fileOutput(_ output: AVCaptureFileOutput, didFinishRecordingTo fileURL: URL, from connections: [AVCaptureConnection], error: Error?) {
        DispatchQueue.main.async {
            self.recording = false
            self.transitioning = false
        }
        let completed = error == nil || ((error as NSError?)?.userInfo[AVErrorRecordingSuccessfullyFinishedKey] as? Bool == true)
        guard completed else {
            try? FileManager.default.removeItem(at: fileURL)
            message("Could not finish recording.")
            return
        }
        save({
            PHAssetCreationRequest.forAsset().addResource(with: .video, fileURL: fileURL, options: nil)
        }, completion: { saved in
            if saved { try? FileManager.default.removeItem(at: fileURL) }
        })
    }
}
