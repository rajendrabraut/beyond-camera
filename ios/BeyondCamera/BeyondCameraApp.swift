import AVFoundation
import SwiftUI
import UIKit
import UniformTypeIdentifiers

@main
struct BeyondCameraApp: App {
    var body: some Scene {
        WindowGroup { CameraScreen() }
    }
}

private struct CameraScreen: View {
    @StateObject private var camera = CameraController()
    @Environment(\.scenePhase) private var phase
    @State private var video = false
    @State private var chooseFolder = false
    @State private var showDestination = false

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            CameraPreview(session: camera.session)
                .ignoresSafeArea()
            VStack {
                HStack {
                    if camera.recording {
                        Label("Recording", systemImage: "record.circle")
                            .foregroundStyle(.red)
                    }
                    Spacer()
                    Button {
                        showDestination = true
                    } label: {
                        Image(systemName: "folder").font(.title2)
                            .frame(width: 50, height: 50)
                            .background(.black.opacity(0.52), in: Circle())
                    }
                    .accessibilityLabel("Save destination")
                    .disabled(camera.recording || camera.transitioning)
                    Button {
                        camera.switchCamera()
                    } label: {
                        Image(systemName: "camera.rotate")
                            .font(.title2)
                            .frame(width: 50, height: 50)
                            .background(.black.opacity(0.52), in: Circle())
                    }
                    .accessibilityLabel("Switch camera")
                    .disabled(!camera.ready || camera.recording || camera.transitioning)
                }
                Spacer()
                if let status = camera.status {
                    Text(status)
                        .font(.subheadline)
                        .multilineTextAlignment(.center)
                        .padding(12)
                        .background(.black.opacity(0.72), in: RoundedRectangle(cornerRadius: 12))
                        .accessibilityLiveRegionIfAvailable()
                    if camera.permissionDenied {
                        Button("Open Settings") {
                            if let url = URL(string: UIApplication.openSettingsURLString) {
                                UIApplication.shared.open(url)
                            }
                        }
                        .padding(.bottom, 8)
                    }
                }
                HStack(spacing: 24) {
                    mode("Photo", isVideo: false)
                    mode("Video", isVideo: true)
                }
                .font(.headline)
                .disabled(camera.recording || camera.transitioning)
                Button {
                    if video { camera.toggleVideo() } else { camera.takePhoto() }
                } label: {
                    ZStack {
                        Circle().strokeBorder(.white, lineWidth: 3).frame(width: 76, height: 76)
                        RoundedRectangle(cornerRadius: camera.recording ? 8 : 32)
                            .fill(video ? .red : .white)
                            .frame(width: camera.recording ? 30 : 60, height: camera.recording ? 30 : 60)
                    }
                }
                .padding(.top, 8)
                .accessibilityLabel(video ? (camera.recording ? "Stop recording" : "Record video") : "Take photo")
                .disabled(!camera.ready || camera.transitioning)
            }
            .padding()
        }
        .foregroundStyle(.white)
        .preferredColorScheme(.dark)
        .confirmationDialog("Save destination", isPresented: $showDestination, titleVisibility: .visible) {
            Button("Use Photos") { camera.setFolder(nil) }
            Button("Choose Files folder") { chooseFolder = true }
        }
        .fileImporter(isPresented: $chooseFolder, allowedContentTypes: [.folder]) { result in
            if case .success(let folder) = result { camera.setFolder(folder) }
        }
        .task { if phase == .active { camera.activate() } }
        .onChange(of: phase) { state in
            if state == .active { camera.activate() }
            if state == .background { camera.deactivate() }
        }
    }

    private func mode(_ title: String, isVideo: Bool) -> some View {
        Button(title) { video = isVideo }
            .buttonStyle(.plain)
            .foregroundStyle(video == isVideo ? Color.mint : .white)
            .accessibilityAddTraits(video == isVideo ? .isSelected : [])
    }
}

private extension View {
    func accessibilityLiveRegionIfAvailable() -> some View {
        accessibilityAddTraits(.updatesFrequently)
    }
}

private struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.preview.session = session
        view.preview.videoGravity = .resizeAspectFill
        return view
    }

    func updateUIView(_ view: PreviewView, context: Context) {}

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var preview: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }

        override func layoutSubviews() {
            super.layoutSubviews()
            guard let connection = preview.connection, connection.isVideoOrientationSupported else { return }
            switch window?.windowScene?.interfaceOrientation {
            case .landscapeLeft: connection.videoOrientation = .landscapeLeft
            case .landscapeRight: connection.videoOrientation = .landscapeRight
            case .portraitUpsideDown: connection.videoOrientation = .portraitUpsideDown
            default: connection.videoOrientation = .portrait
            }
        }
    }
}
