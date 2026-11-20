import AppKit
import SwiftUI

/// The app's icon: the Android launcher's coral crane in flight on a light tile, drawn to Apple's icon grid (a 824-pt
/// rounded square in a 1024-pt canvas with its soft shadow). `BORDERLESS_ICON=<dir>.iconset` writes every size the
/// `.icns` needs and quits; `./build.sh icon` turns them into `Resources/AppIcon.icns`.
struct AppIconArt: View {
    var size: CGFloat

    /// The tile's light colours stay whatever the system's appearance: the icon is the same in the Dock and Finder.
    private static let coral = Color(red: 0xE0 / 255, green: 0x65 / 255, blue: 0x4D / 255)
    private static let soft = Color(red: 0xFC / 255, green: 0xE9 / 255, blue: 0xE3 / 255)
    private static let warm = Color(red: 0xF7 / 255, green: 0xF0 / 255, blue: 0xEC / 255)

    var body: some View {
        let k = size / 1024
        ZStack {
            RoundedRectangle(cornerRadius: 185 * k, style: .continuous)
                .fill(LinearGradient(colors: [.white, Self.warm], startPoint: .top, endPoint: .bottom))
                .shadow(color: .black.opacity(0.22), radius: 12 * k, y: 8 * k)
            // The glow of the connect button, behind the bird.
            Circle()
                .fill(RadialGradient(colors: [Self.soft, Self.soft.opacity(0)], center: .center, startRadius: 0, endRadius: 330 * k))
                .frame(width: 660 * k, height: 660 * k)
            Crane()
                .fill(Self.coral)
                .frame(width: 540 * k, height: 540 * k)
                .offset(x: 6 * k, y: -8 * k)
        }
        .frame(width: 824 * k, height: 824 * k)
        .frame(width: size, height: size)
    }
}

enum AppIcon {
    /// The sizes of an `.iconset`: (file name, pixels).
    static let sizes: [(String, Int)] = [16, 32, 128, 256, 512].flatMap { s in
        [("icon_\(s)x\(s).png", s), ("icon_\(s)x\(s)@2x.png", s * 2)]
    }

    @MainActor static func runIfAsked() {
        guard let dir = ProcessInfo.processInfo.environment["BORDERLESS_ICON"] else { return }
        try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        for (name, px) in sizes {
            let renderer = ImageRenderer(content: AppIconArt(size: CGFloat(px)))
            renderer.scale = 1
            guard let image = renderer.cgImage,
                  let png = NSBitmapImageRep(cgImage: image).representation(using: .png, properties: [:]) else {
                FileHandle.standardError.write(Data("icon: \(name) failed\n".utf8))
                exit(1)
            }
            try? png.write(to: URL(fileURLWithPath: dir).appendingPathComponent(name))
        }
        exit(0)
    }
}
