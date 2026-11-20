import Foundation

/// Interpolate the same polyline the chart draws, in plot coordinates (also correct for log axes).
public enum ChartProbe {
    /// Median chunks in data coordinates; retain the central sample's time in every chunk.
    public static func medianTrend(_ points: [CGPoint], chunkSize: Int) -> [CGPoint] {
        let size = max(chunkSize, 1)
        return stride(from: 0, to: points.count, by: size).map { start in
            let end = min(start + size, points.count)
            let values = points[start..<end].map(\.y).sorted()
            let mid = values.count / 2
            let median = values.count % 2 == 1 ? values[mid] : (values[mid - 1] + values[mid]) / 2
            return CGPoint(x: points[start + mid].x, y: median)
        }
    }

    public static func point(at x: CGFloat, on points: [CGPoint], stepped: Bool = false,
                             tail: CGFloat = 0) -> CGPoint? {
        guard let first = points.first, let last = points.last else { return nil }
        if x == first.x { return first }
        guard x >= first.x, x <= last.x + tail else { return nil }
        if x >= last.x { return CGPoint(x: x, y: last.y) }
        for (a, b) in zip(points, points.dropFirst()) where x >= a.x && x <= b.x {
            if x == b.x { return b }
            guard b.x > a.x else { return b }
            let y = stepped ? a.y : a.y + (b.y - a.y) * (x - a.x) / (b.x - a.x)
            return CGPoint(x: x, y: y)
        }
        return nil
    }
}
