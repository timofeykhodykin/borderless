import AppKit
import AVFoundation
import SwiftUI

/// The camera QR scanner (Android's `ScanContract` screen, from the "Scan QR" action in the Add dialog):
/// a live preview box that hands the first QR code's text back. The camera is asked for on first use
/// (`NSCameraUsageDescription`); a refusal, or a Mac without a camera, degrades to an explained line —
/// the QR-from-image path next to it keeps working.
///
/// The build signs the app with `Support/Entitlements.plist` carrying
/// `com.apple.security.device.camera` — without it the hardened runtime would make
/// `AVCaptureDevice.requestAccess` answer "denied" no matter what.
struct QRCameraPane: View {
    /// The decoded text, once — the caller appends it to the field and closes the pane.
    var found: (String) -> Void
    var close: () -> Void
    var snapshot = false

    enum Status { case asking, live, denied, unavailable }
    @State private var status: Status = .asking
    @StateObject private var scanner = QRCameraScanner()

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            switch status {
            case .asking:
                HStack(spacing: 7) {
                    Spinner().frame(width: 12, height: 12)
                    Text(macText("mac.add.camera.asking", "Asking for the camera…")).font(Fonts.body(11))
                        .foregroundStyle(Palette.textSecondary).textSelection(.enabled)
                }
            case .live:
                QRCameraPreview(scanner: scanner)
                    .frame(height: 200)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                    .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(Palette.textMuted.opacity(0.55), lineWidth: 1))
            case .denied:
                Text(macText("mac.add.camera.denied", "The camera is off for Border(less). Allow it in System Settings → Privacy & Security → Camera, or use “QR from image” instead."))
                    .font(Fonts.body(11)).foregroundStyle(Palette.danger).fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
                HStack(spacing: 6) {
                    MacButton(title: macText("mac.add.camera.settings", "Open System Settings"), symbol: "gear") {
                        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Camera") {
                            NSWorkspace.shared.open(url)
                        }
                    }
                    MacButton(title: L("cancel")) { close() }
                }
            case .unavailable:
                Text(macText("mac.add.camera.none", "No camera on this Mac — use “QR from image” instead."))
                    .font(Fonts.body(11)).foregroundStyle(Palette.danger).fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 8).fill(Palette.card.opacity(0.6)))
        .onAppear { if snapshot { status = .unavailable } else { checkPermission() } }
        .onDisappear { scanner.stop() }
    }

    private func checkPermission() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: start()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { granted in
                Task { @MainActor in
                    if granted { self.start() } else { self.status = .denied }
                }
            }
        case .denied, .restricted: status = .denied
        @unknown default: status = .denied
        }
    }

    private func start() {
        scanner.start(found: { text in found(text) }) { ok in
            status = ok ? .live : .unavailable
        }
    }
}

/// The capture session behind the pane: video in, `AVCaptureMetadataOutput` QR codes out. `start`/`stop`
/// are called on the main actor (SwiftUI), but the session itself is configured and run on `queue` —
/// `startRunning`/`stopRunning` block and must not sit on the window's turn. The delegate fires on
/// `queue` too, then hops to the main actor to hand the code over.
final class QRCameraScanner: NSObject, ObservableObject, AVCaptureMetadataOutputObjectsDelegate {
    private let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "app.borderless.qr")
    /// Written on the main actor in `start`/`stop`, read on the main actor after the delegate's hop.
    private var onFound: ((String) -> Void)?
    /// The first code wins — a camera keeps delivering the same code every frame otherwise. Read and
    /// written on `queue` only — the reset rides `start`'s own queued block (a stale delegate call
    /// queued earlier runs before it, a new one after), so the plain bool needs no lock.
    private var delivered = false

    /// `done` on the main actor: false = no camera (or one the system refuses to hand over) — the pane
    /// shows its fallback.
    func start(found: @escaping (String) -> Void, done: @escaping @MainActor (Bool) -> Void) {
        onFound = found
        queue.async { [session, queue, weak self] in
            self?.delivered = false
            let ok = self.flatMap { scanner -> Bool? in
                guard let device = AVCaptureDevice.default(for: .video),
                      let input = try? AVCaptureDeviceInput(device: device),
                      session.canAddInput(input) else { return nil }
                let output = AVCaptureMetadataOutput()
                guard session.canAddOutput(output) else { return nil }
                session.addInput(input)
                session.addOutput(output)
                guard output.availableMetadataObjectTypes.contains(.qr) else { return nil }
                output.metadataObjectTypes = [.qr]
                output.setMetadataObjectsDelegate(scanner, queue: queue)
                return true
            } ?? false
            if ok { session.startRunning() }
            Task { await done(ok) }
        }
    }

    func stop() {
        onFound = nil
        queue.async { [session] in if session.isRunning { session.stopRunning() } }
    }

    nonisolated func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject],
                                    from connection: AVCaptureConnection) {
        guard !delivered,
              let text = metadataObjects.compactMap({ ($0 as? AVMetadataMachineReadableCodeObject)?.stringValue }).first
        else { return }
        delivered = true
        // The hop carries the scanner in a box: `delivered` makes the hand-off a once, and
        // `stop`/`onFound` stay main-side by the class's own discipline, so nothing races.
        let box = Box(scanner: self)
        Task { @MainActor in
            guard let self = box.scanner else { return }
            self.stop()
            self.onFound?(text)
        }
    }

    /// The queue→main hand-off above: `sending` a non-Sendable `self` is refused, so the weak
    /// reference crosses the actor boundary in this wrapper.
    private struct Box: @unchecked Sendable { weak var scanner: QRCameraScanner? }

    /// The preview layer for `QRCameraPreview`, created on the main actor where the view is made.
    var previewLayer: AVCaptureVideoPreviewLayer {
        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        return layer
    }
}

/// The live picture, as an `NSView` whose layer is the session's preview layer.
private struct QRCameraPreview: NSViewRepresentable {
    var scanner: QRCameraScanner

    func makeNSView(context: Context) -> NSView {
        let view = NSView()
        view.wantsLayer = true
        view.layer = scanner.previewLayer
        return view
    }

    func updateNSView(_ view: NSView, context: Context) {}
}
