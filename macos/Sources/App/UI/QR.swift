import AppKit
import CoreImage
import CoreImage.CIFilterBuiltins
import ImageIO

/// QR codes: made for sharing (Android `share/Qr.kt` encodes with ZXing), read from a picture when adding.
enum QR {
    /// One QR code's byte-mode capacity at the low error correction Android's share sheet uses (ZXing
    /// EC-L): version 40, 2953 bytes. `CIQRCodeGenerator` picks the smallest version that fits like
    /// ZXing does, so past this it fails — the share dialog's "too big" line (F-SHARE-0022-1).
    static let maxChars = 2953

    /// Pictures wider or taller than this are shrunk before detection: a photo out of the camera is megapixels
    /// the detector doesn't need (a QR module is many pixels even shrunk).
    private static let maxSide = 2000

    static func image(_ text: String, size: CGFloat) -> NSImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        // "L" — Android's ZXing EC level (Qr.kt): the biggest payload a code can take, at the cost of
        // less damage tolerance; the share text's copies are the fallback anyway.
        filter.correctionLevel = "L"
        guard let output = filter.outputImage else { return nil }
        let scale = size / output.extent.width
        let scaled = output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let rep = NSCIImageRep(ciImage: scaled)
        let image = NSImage(size: rep.size)
        image.addRepresentation(rep)
        return image
    }

    /// Every QR code in the picture at `url`, joined by lines; nil when there is none. The read itself runs off
    /// the caller's thread: a big photo's detection can take a moment.
    static func decode(_ url: URL) async -> String? {
        await Task.detached { decodeImage(url) }.value
    }

    private static func decodeImage(_ url: URL) -> String? {
        guard let image = thumbnail(url) ?? CIImage(contentsOf: url),
              let detector = CIDetector(ofType: CIDetectorTypeQRCode, context: nil, options: [CIDetectorAccuracy: CIDetectorAccuracyHigh])
        else { return nil }
        let found = detector.features(in: image).compactMap { ($0 as? CIQRCodeFeature)?.messageString }
        if !found.isEmpty { return found.joined(separator: "\n") }
        // Like Android's retry on the inverted luminance source: a light-on-dark QR (a screenshot of a
        // dark-themed screen) reads once inverted.
        let inverted = image.applyingFilter("CIColorInvert")
        let retry = detector.features(in: inverted).compactMap { ($0 as? CIQRCodeFeature)?.messageString }
        return retry.isEmpty ? nil : retry.joined(separator: "\n")
    }

    /// The picture at most [maxSide] on a side, EXIF rotation applied; nil when it can't be made — the caller
    /// reads the file itself then.
    private static func thumbnail(_ url: URL) -> CIImage? {
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxSide,
        ]
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
              let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
        else { return nil }
        return CIImage(cgImage: cg)
    }
}
