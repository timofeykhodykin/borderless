import Shared
import SwiftUI

/// Line colours of chart series (Android `SeriesColors`, light / dark).
enum SeriesColors {
    static let all: [Color] = zip(
        [0x2A78D6, 0xEB6834, 0x1BAF7A, 0xEDA100, 0xE87BA4, 0x008300, 0x4A3AA7, 0xE34948] as [UInt32],
        [0x64A1EE, 0xF78955, 0x3FBE90, 0xEDB345, 0xE98DB2, 0x54B85B, 0x9887E8, 0xEF6661] as [UInt32]
    ).map { Palette.pair($0.0, $0.1) }
}

/// Chart bands of the connection's states (Android `StateSet.*.band`, `off`); "through a server" stays clear.
func bandColor(_ phase: Phase) -> Color? {
    switch phase {
    case .connected, .searching: nil
    case .off: Palette.off
    case .direct, .paused, .noNetwork, .limited: phase.tone!.band
    }
}

/// The colour of a state on the timeline (Android `phaseColor`).
func phaseColor(_ phase: Phase) -> Color {
    switch phase {
    case .connected: Palette.accent
    case .searching: Palette.accentContainer
    case .direct, .paused, .noNetwork, .limited: phase.tone!.strong
    case .off: Palette.card
    }
}

struct ChartSeries: Identifiable {
    /// Its identity in `ForEach`: the name — stable across the series' rebuild on every redraw (a fresh `UUID()`
    /// per construction made `ForEach` see all-new rows each time).
    var id: String { name }
    var name: String
    var color: Color
    var points: [(Date, Double?)]
    var fill = false
    var fillOpacity = 0.3
    var lineWidth: CGFloat = 1.6
    /// A pale wide line under it (Android's "best of all").
    var halo = false
    /// Failed probes per bucket as (time, share of failures) — the ticks under the plot when the
    /// chart's `failures` is on (Android `StatsMath.Point.failed/total` on the first series).
    var failureTicks: [(Date, Double)] = []
}

/// Y axis (Android `Axes.kt` `YScale`). Zooming works in "units": ln(value) for a log scale, the
/// value itself for a linear one — the viewport stores the slice of that unit space in normalized
/// 0…1. [min]…[max] is the full (zoomed-out) range; [divide] labels show `raw / divide` (a provider's
/// MB axis read in GB); [integer] keeps ticks to whole numbers.
struct YScale {
    var min: Double
    var max: Double
    var log: Bool
    var integer: Bool = true
    var divide: Double = 1

    static func linear(_ max: Double, integer: Bool = true, divide: Double = 1) -> YScale {
        YScale(min: 0, max: max <= 0 ? 1 : max, log: false, integer: integer, divide: divide)
    }
    /// Logarithmic between the two.
    static func log(_ lo: Double, _ hi: Double) -> YScale { YScale(min: lo, max: hi, log: true) }
    /// Latency, logarithmic like the colour scale: 20 ms … 3 s (Android `YScale.latency()`).
    static var latency: YScale { .log(20, 3000) }

    /// The unit space the axis' math runs in.
    func unit(_ v: Double) -> Double {
        let c = Swift.min(Swift.max(v, min), max)
        return log ? Foundation.log(c) : c
    }
    func value(_ u: Double) -> Double { log ? Foundation.exp(u) : u }
    var uMin: Double { unit(min) }
    var uMax: Double { unit(max) }

    /// Where [v] lands between 0 and 1 of the axis (the viewport's normalized space).
    func position(_ v: Double) -> Double {
        uMax > uMin ? (unit(v) - uMin) / (uMax - uMin) : 0
    }
    /// The value a normalized position stands for.
    func value(at p: Double) -> Double { value(uMin + p * (uMax - uMin)) }

    /// Smallest visible span in units: about ±12 % around a latency, a tenth of a linear axis.
    var minSpan: Double {
        log ? Foundation.log(1.25) : Swift.max((max - min) / 10, integer ? 2 : 0)
    }

    /// Tick values for the normalized visible slice [p0]…[p1] (Android `YScale.ticks`).
    func ticks(_ p0: Double, _ p1: Double) -> [Double] {
        let lo = value(at: p0), hi = value(at: p1)
        if log && hi / lo >= 4 { return Self.logTicks(lo: lo, hi: hi) }
        return Self.niceTicks(lo: lo, hi: hi, integer: integer)
    }

    /// 1-2-5-style ticks per decade, the densest set that still leaves at most five ticks.
    static func logTicks(lo: Double, hi: Double) -> [Double] {
        let sets: [[Double]] = [[1], [1, 3], [1, 2, 5], [1, 1.5, 2, 3, 5]]
        var best: [Double] = []
        for set in sets {
            var ticks: [Double] = []
            var p = Foundation.pow(10, Foundation.floor(Foundation.log10(lo)))
            while p <= hi {
                for m in set {
                    let v = m * p
                    if v >= lo - 1e-9 && v <= hi + 1e-9 { ticks.append(v) }
                }
                p *= 10
            }
            if ticks.count > 5 { break }
            best = ticks
        }
        return best
    }

    /// Linear ticks with a 1/2/2.5/5 × 10ⁿ step, about four to six of them.
    static func niceTicks(lo: Double, hi: Double, integer: Bool) -> [Double] {
        let span = hi - lo
        guard span > 0 else { return [lo] }
        let raw = span / 5
        let mag = Foundation.pow(10, Foundation.floor(Foundation.log10(raw)))
        var step = [1.0, 2.0, 2.5, 5.0, 10.0].map { $0 * mag }.first { $0 >= raw } ?? 10 * mag
        if integer { step = Swift.max(1, step.rounded()) }
        var out: [Double] = []
        var v = (lo / step - 1e-9).rounded(.up) * step
        while v <= hi + 1e-9 { out.append(v); v += step }
        return out
    }

    /// The axis label (Android `YScale.label`): the value over [divide], then `plainLabel` —
    /// "950", "1.5k", "12k", "3.2M"; a non-integer divided value under 10 keeps two trimmed decimals.
    func label(_ raw: Double) -> String {
        let v = raw / divide
        if divide != 1, abs(v) < 10, abs(v - v.rounded()) > 1e-6 {
            var s = String(format: "%.2f", locale: Locale.current, v)
            while s.hasSuffix("0") { s.removeLast() }
            if s.last == "." || s.last == "," { s.removeLast() }
            return s
        }
        return Self.plainLabel(v)
    }

    /// Short enough for the narrow label column whatever the amount: "950", "1.5k", "12k", "3.2M".
    static func plainLabel(_ v: Double) -> String {
        let a = abs(v)
        if a >= 1_000_000 {
            let m = v / 1_000_000
            return abs(m - m.rounded()) < 1e-6 ? "\(Int(m.rounded()))M" : String(format: "%.1f", locale: Locale.current, m) + "M"
        }
        if a >= 1000 {
            let k = v / 1000
            return abs(k - k.rounded()) < 1e-6 ? "\(Int(k.rounded()))k" : String(format: "%.1f", locale: Locale.current, k) + "k"
        }
        return abs(v - v.rounded()) < 1e-6 ? "\(Int(v.rounded()))" : String(format: "%.1f", locale: Locale.current, v)
    }
}

/// A time chart drawn like Android's `TimeChart`: state bands behind, horizontal grid with value labels on the left,
/// time labels below, lines (stepped for counts, filled when asked) and a legend above.
///
/// Gestures (the desktop form of Android's touch ones, per the plan): a trackpad pinch zooms the time
/// axis around the pointer (⌥ + pinch zooms the value axis instead), a two-finger scroll or a mouse
/// drag pans a zoomed window, a double click zooms all the way out, and hovering shows a crosshair with
/// each named series' nearest value where the legend sits (Android shows the same under the finger).
/// The window lives in `viewport` and never leaves `from`…`to` — there is no more data beyond it.
struct TimeChart: View {
    var series: [ChartSeries]
    var bands: [PhaseSpan]
    var from: Date
    var to: Date
    var scale: YScale
    var stepped = false
    var height: CGFloat = 140
    /// Failed probes as ticks below the plot (Android `failures`): read off the first series'
    /// `failureTicks`, taller = larger share of failures in the bucket.
    var failures = false
    /// Join points up to this far apart (Android `connectGapMs` = 30 min); nil joins only
    /// neighbouring buckets. For sparse but real data: server pings also measured with the tunnel off.
    var connectGapMs: TimeInterval? = nil
    /// Legend entry for the unshaded background (the normal state), shown even without any band
    /// (Android `clearLabel` — "through a server" on the stats charts).
    var clearLabel: String? = nil
    /// Vertical lines with labels at moments — strategy changes (Android `markers`).
    var markers: [ChartMarker] = []
    /// How a value reads under the pointer (the probe line). The axis labels come from `YScale.label`
    /// regardless — Android reads the probe in ms ("1000 ms") while the axis keeps its short labels.
    var probeFormat: ((Double) -> String)? = nil

    @Environment(\.snapshot) private var snapshot
    @State private var viewport = ChartViewport()
    /// The moment under the pointer (the crosshair), or nil off the plot.
    @State private var hoverAt: Date?
    /// The magnification reported at the last pinch event: the gesture's value is cumulative.
    @State private var lastMag: CGFloat = 1
    /// The axis the pinch in flight zooms (⌥ = the value axis), latched at its first event —
    /// sampling the key per event would flip the axis if ⌥ is pressed or let go mid-gesture.
    @State private var pinchValueAxis: Bool?
    /// The drag translation consumed so far (a gesture reports the total, the pan needs deltas).
    @State private var dragged: CGSize = .zero

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 8) {
                // The readout of the moment under the pointer floats over the legend — swapped into
                // its place it would wrap differently and the plot's height would jump on hover.
                Legend(series: series.filter { !$0.name.isEmpty }, bands: bands, from: from, to: to,
                       clearLabel: clearLabel)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .overlay(alignment: .topLeading) {
                        if let probe { ProbeRow(probe: probe).background(Palette.background) }
                    }
                if viewport.zoomed, !snapshot { resetPill }
            }
            // Marker labels above the plot, each starting at its line. Placed from the latest backwards:
            // when labels would collide, the later one wins — a burst of changes shows the state it ended in.
            if markers.isEmpty {
                Spacer().frame(height: 6)
            } else {
                Canvas { ctx, size in
                    let view = visibleTime, span = max(view.upperBound.timeIntervalSince(view.lowerBound), 1)
                    let plot = plot(in: size)
                    func x(_ t: Date) -> CGFloat {
                        plot.minX + CGFloat(t.timeIntervalSince(view.lowerBound) / span) * plot.width
                    }
                    var nextStart = CGFloat.infinity
                    for m in markers.sorted(by: { ($0.edge ? Date.distantPast : $0.at) > ($1.edge ? Date.distantPast : $1.at) }) {
                        guard m.edge || (m.at > view.lowerBound && m.at < view.upperBound) else { continue }
                        let xx = m.edge ? plot.minX : x(m.at)
                        let text = ctx.resolve(Text(m.label).font(Fonts.body(10)).foregroundStyle(Palette.textSecondary))
                        let w = text.measure(in: size).width
                        let lx = min(xx - (m.edge ? 0 : 2), size.width - w)
                        guard lx + w + 8 <= nextStart else { continue }
                        ctx.draw(text, at: CGPoint(x: lx, y: 0), anchor: .topLeading)
                        nextStart = lx
                    }
                }
                .frame(height: 14)
            }
            GeometryReader { g in
                let size = g.size
                Canvas { ctx, _ in draw(ctx, size) }
                    .contentShape(Rectangle())
                    .onContinuousHover { phase in
                        if case .active(let p) = phase { hoverAt = time(atX: p.x, in: size) } else { hoverAt = nil }
                    }
                    .onTapGesture(count: 2) { viewport = ChartViewport() }
                    .gesture(DragGesture(minimumDistance: 4)
                        .onChanged { v in
                            panBy(CGSize(width: v.translation.width - dragged.width,
                                         height: v.translation.height - dragged.height), in: size)
                            dragged = v.translation
                        }
                        .onEnded { _ in dragged = .zero })
                    .gesture(MagnifyGesture()
                        .onChanged { v in
                            let onValue = pinchValueAxis ?? chartOptionDown()
                            if pinchValueAxis == nil { pinchValueAxis = onValue }
                            let delta = lastMag > 0 ? v.magnification / lastMag : 1
                            lastMag = v.magnification
                            if onValue { zoomValue(by: delta, anchorY: v.startAnchor.y * size.height, in: size) }
                            else { zoomTime(by: delta, anchorX: v.startAnchor.x * size.width, in: size) }
                        }
                        .onEnded { _ in lastMag = 1; pinchValueAxis = nil })
                    .overlay {
                        // An AppKit view can't appear in a rendered snapshot; there is no pointer there anyway.
                        if !snapshot { ScrollWheelCatch(onScroll: { scrollBy($0, in: size) }) }
                    }
            }
            .frame(height: height)
        }
        .onChange(of: resetKey) { viewport = ChartViewport(); hoverAt = nil }
    }

    /// What the zoom is allowed to outlive: a refresh of the same period slides `from`/`to` forward together, so it
    /// must not reset the window; a new period must. The span goes into a log bucket — a quarter-step of a
    /// doubling — so a minute-step refresh (or the slow growth of "all time") keeps the key still while any real
    /// period change flips it. The chart's subject (which target, which tab) needs no key: a page switch rebuilds
    /// the view and its state with it. Series names are deliberately out — the top-4 membership changing between
    /// polls must not drop the user's zoom.
    private var resetKey: Int {
        Int((Foundation.log2(max(fullSpan, 1)) * 4).rounded())
    }

    private var fullSpan: TimeInterval { to.timeIntervalSince(from) }

    /// The drawing area inside the axis labels (a left column of value labels, a row of time labels below).
    private func plot(in size: CGSize) -> CGRect {
        CGRect(x: 30, y: 4, width: size.width - 30, height: size.height - 16 - 4)
    }

    /// The visible time window, clamped into the loaded range (Android `visibleTime`).
    private var visibleTime: ClosedRange<Date> {
        guard let v = viewport.time else { return from...to }
        let span = min(v.upperBound.timeIntervalSince(v.lowerBound), fullSpan)
        let start = min(max(v.lowerBound, from), to.addingTimeInterval(-span))
        return start...start.addingTimeInterval(span)
    }

    /// The visible slice of the value axis in normalized units (Android `visibleValue`): 0…1 is the whole scale.
    private var visibleValue: ClosedRange<Double> {
        guard let v = viewport.value else { return 0...1 }
        let span = min(v.upperBound - v.lowerBound, 1)
        let start = min(max(v.lowerBound, 0), 1 - span)
        return start...(start + span)
    }

    /// The narrowest sensible zoom of the value axis (Android `YScale.minSpan`), in normalized units.
    private var minValueSpan: Double {
        let whole = scale.uMax - scale.uMin
        return whole > 0 ? scale.minSpan / whole : 1
    }

    /// The moment under the pointer, or nil over the label margins.
    private func time(atX px: CGFloat, in size: CGSize) -> Date? {
        let plot = plot(in: size)
        guard px >= plot.minX, px <= plot.maxX, plot.width > 0 else { return nil }
        let view = visibleTime
        return view.lowerBound.addingTimeInterval(Double((px - plot.minX) / plot.width) * view.upperBound.timeIntervalSince(view.lowerBound))
    }

    /// A pinch on the time axis: keep the moment under the pointer under it, clamp the window to
    /// `minTimeSpan`…the whole range, and treat a pinch back to (almost) full range as a zoom out.
    private func zoomTime(by factor: Double, anchorX: CGFloat, in size: CGSize) {
        guard factor != 1 else { return }
        let view = visibleTime, plot = plot(in: size)
        let span = view.upperBound.timeIntervalSince(view.lowerBound)
        let newSpan = min(max(span / factor, min(ChartViewport.minTimeSpan, fullSpan)), fullSpan)
        let frac = min(max((anchorX - plot.minX) / plot.width, 0), 1)
        let anchor = view.lowerBound.addingTimeInterval(frac * span)
        let start = min(max(anchor.addingTimeInterval(-frac * newSpan), from), to.addingTimeInterval(-newSpan))
        viewport.time = newSpan >= fullSpan * 0.999 ? nil : start...start.addingTimeInterval(newSpan)
    }

    /// A pinch on the value axis (⌥): the same in normalized units around the pointer's height.
    private func zoomValue(by factor: Double, anchorY: CGFloat, in size: CGSize) {
        guard factor != 1 else { return }
        let view = visibleValue, plot = plot(in: size)
        let span = view.upperBound - view.lowerBound
        let newSpan = min(max(span / factor, min(minValueSpan, 1)), 1)
        // 0 at the plot's bottom edge: larger values sit higher.
        let frac = min(max((plot.maxY - anchorY) / plot.height, 0), 1)
        let anchor = view.lowerBound + frac * span
        let start = min(max(anchor - frac * newSpan, 0), 1 - newSpan)
        viewport.value = newSpan >= 0.999 ? nil : start...(start + newSpan)
    }

    /// A mouse drag pans the zoomed axes: the content follows the pointer (Android's PAN — a drag down
    /// on the value axis shows the larger values above).
    private func panBy(_ d: CGSize, in size: CGSize) {
        let plot = plot(in: size)
        if let v = viewport.time {
            let span = v.upperBound.timeIntervalSince(v.lowerBound)
            let start = min(max(v.lowerBound.addingTimeInterval(-d.width / plot.width * span), from), to.addingTimeInterval(-span))
            viewport.time = start...start.addingTimeInterval(span)
        }
        if let v = viewport.value {
            let span = v.upperBound - v.lowerBound
            let start = min(max(v.lowerBound + d.height / plot.height * span, 0), 1 - span)
            viewport.value = start...(start + span)
        }
    }

    /// A two-finger scroll pans the zoomed axis that matches the dominant direction and reports whether it
    /// was used (else the page scrolls). With natural scrolling the deltas follow the fingers — as a drag's
    /// translation does in `panBy` — so the window slides the same way the drag would take it: a swipe left
    /// (negative delta) moves the window to later times like a leftward drag.
    private func scrollBy(_ d: CGSize, in size: CGSize) -> Bool {
        let plot = plot(in: size)
        if d.width != 0, abs(d.width) >= abs(d.height), let v = viewport.time {
            let span = v.upperBound.timeIntervalSince(v.lowerBound)
            let start = min(max(v.lowerBound.addingTimeInterval(-d.width / plot.width * span), from), to.addingTimeInterval(-span))
            viewport.time = start...start.addingTimeInterval(span)
            return true
        }
        if d.height != 0, abs(d.height) > abs(d.width), let v = viewport.value {
            let span = v.upperBound - v.lowerBound
            let start = min(max(v.lowerBound + d.height / plot.height * span, 0), 1 - span)
            viewport.value = start...(start + span)
            return true
        }
        return false
    }

    /// The moment and each named series' nearest value under the pointer (Android's cursor header).
    private var probe: Legend.Probe? {
        guard let t = hoverAt else { return nil }
        let view = visibleTime
        let narrow = view.upperBound.timeIntervalSince(view.lowerBound) <= 900
        var time = t.formatted(narrow ? .dateTime.hour(.twoDigits(amPM: .omitted)).minute(.twoDigits).second(.twoDigits)
                                      : .dateTime.hour(.twoDigits(amPM: .omitted)).minute(.twoDigits))
        if fullSpan > 36 * 3600 { time = t.formatted(.dateTime.day().month(.abbreviated)) + ", " + time }
        let values = series.filter { !$0.name.isEmpty }.map { s in
            let p = s.points.min { abs($0.0.timeIntervalSince(t)) < abs($1.0.timeIntervalSince(t)) }
            let f = probeFormat ?? { scale.label($0) }
            return (s.color, p == nil ? "\(s.name): —" : p!.1 == nil ? "\(s.name): ✕" : "\(s.name): \(f(p!.1!))")
        }
        return Legend.Probe(time: time, values: values)
    }

    /// A way back to the whole range besides double click (Android's "Reset" pill, shown only when zoomed).
    private var resetPill: some View {
        Button {
            viewport = ChartViewport()
            hoverAt = nil
        } label: {
            HStack(spacing: 4) {
                Image(systemName: "minus.magnifyingglass").font(.system(size: 10, weight: .semibold))
                Text(L("zoom_reset")).font(Fonts.body(11, bold: true))
            }
            .padding(.horizontal, 9).padding(.vertical, 3)
            .foregroundStyle(Palette.accentStrong)
            .background(Capsule().fill(Palette.accentSoft))
        }
        .buttonStyle(.plain)
    }

    private func draw(_ ctx: GraphicsContext, _ size: CGSize) {
        let plot = plot(in: size)
        let view = visibleTime, vv = visibleValue
        let span = max(view.upperBound.timeIntervalSince(view.lowerBound), 1)
        func x(_ t: Date) -> CGFloat { plot.minX + CGFloat(t.timeIntervalSince(view.lowerBound) / span) * plot.width }
        func y(_ v: Double) -> CGFloat {
            let pos = min(max(scale.position(v), 0), 1)
            return plot.maxY - CGFloat((pos - vv.lowerBound) / (vv.upperBound - vv.lowerBound)) * plot.height
        }
        // Bands, grid, lines and the crosshair live inside the plot; labels sit in the margins around it.
        var plotCtx = ctx
        plotCtx.clip(to: Path(plot))

        // State bands (the tunnel off, without a server…): fills first, eco hatching over them,
        // dashed edges only where a band actually opens or closes inside the window — a week of
        // short spells would be a forest of dashes (Android `BAND_EDGE_DP`).
        for b in bands where !b.eco {
            guard let c = bandColor(b.phase), b.to > view.lowerBound, b.from < view.upperBound else { continue }
            let r = CGRect(x: x(max(b.from, view.lowerBound)), y: plot.minY,
                           width: max(x(min(b.to, view.upperBound)) - x(max(b.from, view.lowerBound)), 0.5),
                           height: plot.height)
            plotCtx.fill(Path(r), with: .color(c))
            guard r.width >= 8 else { continue }
            for edge in [(r.minX, b.from > view.lowerBound), (r.maxX, b.to < view.upperBound)] where edge.1 {
                var p = Path()
                p.move(to: CGPoint(x: edge.0, y: plot.minY))
                p.addLine(to: CGPoint(x: edge.0, y: plot.maxY))
                plotCtx.stroke(p, with: .color(Palette.textMuted.opacity(0.6)), style: StrokeStyle(lineWidth: 0.8, dash: [3, 3]))
            }
        }
        // The eco intervals: diagonal hatching over whatever is behind (Android `BandKind.ECO`).
        for b in bands where b.eco && b.to > view.lowerBound && b.from < view.upperBound {
            let x0 = x(max(b.from, view.lowerBound)), x1 = x(min(b.to, view.upperBound))
            guard x1 - x0 >= 0.5 else { continue }
            var ecoCtx = plotCtx
            ecoCtx.clip(to: Path(CGRect(x: x0, y: plot.minY, width: x1 - x0, height: plot.height)))
            let h = plot.height
            var p = Path()
            var start = (x0 - h - ((x0 - h).truncatingRemainder(dividingBy: 7)))
            while start < x1 {
                p.move(to: CGPoint(x: start, y: plot.maxY))
                p.addLine(to: CGPoint(x: start + h, y: plot.minY))
                start += 7
            }
            ecoCtx.stroke(p, with: .color(Palette.accentStrong.opacity(0.45)), lineWidth: 1.2)
        }
        // Marker lines through the plot; their labels sit in the strip above it.
        for m in markers where m.line && !m.edge && m.at > view.lowerBound && m.at < view.upperBound {
            var p = Path()
            p.move(to: CGPoint(x: x(m.at), y: plot.minY))
            p.addLine(to: CGPoint(x: x(m.at), y: plot.maxY))
            plotCtx.stroke(p, with: .color(Palette.textMuted.opacity(0.6)), lineWidth: 0.8)
        }
        // Recessive grid with value labels (Android `scale.ticks(u0, u1)` — round values only).
        for tick in scale.ticks(vv.lowerBound, vv.upperBound) {
            let pos = scale.position(tick)
            guard pos >= vv.lowerBound - 1e-9, pos <= vv.upperBound + 1e-9 else { continue }
            let yy = y(tick)
            var p = Path()
            p.move(to: CGPoint(x: plot.minX, y: yy))
            p.addLine(to: CGPoint(x: plot.maxX, y: yy))
            plotCtx.stroke(p, with: .color(Palette.outline), lineWidth: 1)
            ctx.draw(Text(scale.label(tick)).font(Fonts.body(10)).foregroundStyle(Palette.textSecondary),
                     at: CGPoint(x: plot.minX - 6, y: yy), anchor: .trailing)
        }
        // Time labels at round local times; midnight shows the date (Android `timeTicks` + the
        // per-tick day/hour pick). Edge labels stay — clamped into the canvas, not dropped.
        let (step, ticks) = timeTicks()
        for t in ticks {
            let midnight = Int((t.timeIntervalSince1970 + Double(Calendar.current.timeZone.secondsFromGMT(for: t)))
                .truncatingRemainder(dividingBy: 86_400)) == 0
            let text = ctx.resolve(Text(step >= 86_400 || midnight
                    ? t.formatted(.dateTime.day().month(.abbreviated))
                    : t.formatted(.dateTime.hour(.twoDigits(amPM: .omitted)).minute(.twoDigits)))
                .font(Fonts.body(10)).foregroundStyle(Palette.textSecondary))
            let w = text.measure(in: size).width
            let xx = min(max(x(t) - w / 2, plot.minX), size.width - w)
            ctx.draw(text, at: CGPoint(x: xx, y: size.height), anchor: .bottomLeading)
        }
        // Series: halo first, then fill, then the measured line; failed buckets and long gaps break
        // it (Android `joinMs`: the connect-gap, the bucket width and 20 dp on screen, whichever
        // is longer — sparse data stays one line and zooming narrows the joins again).
        let msPerPt = span * 1000 / max(Double(plot.width), 1)
        let bucketMs = series.compactMap { s in
            zip(s.points, s.points.dropFirst()).map { $1.0.timeIntervalSince($0.0) * 1000 }.min()
        }.min() ?? 0
        let joinMs = connectGapMs.map { Swift.max($0 * 1000, bucketMs * 1.5, msPerPt * 20) } ?? bucketMs * 3
        let stepTailMs = msPerPt * 4
        for s in series {
            // Points packed tighter than the eye can follow: a line through the medians of runs over a
            // faint measured one (Android's dense/trend); spaced points get dots.
            let inView = s.points.filter { $0.1 != nil && $0.0 >= view.lowerBound && $0.0 <= view.upperBound }
            let gaps = zip(inView, inView.dropFirst()).map { $1.0.timeIntervalSince($0.0) }.sorted()
            let spacing = gaps.isEmpty ? span : gaps[gaps.count / 2]
            let spacingPt = spacing / span * Double(plot.width)
            let dense = stepped ? 0.0 : min(max((2.5 - spacingPt) / 1.0, 0), 1)
            let trend = dense > 0 ? max(2, Int((Double(inView.count) / 40).rounded(.up))) : 1
            let dots = spacingPt >= 14
            drawSeries(s, in: &plotCtx, plot: plot, x: x, y: y, joinMs: joinMs, stepTailMs: stepTailMs,
                       trend: trend, dense: dense, dots: dots)
        }
        if failures, let first = series.first {
            // Failed probes as ticks below the plot; taller = larger share of failures in the bucket.
            for (t, share) in first.failureTicks where share > 0 && t >= view.lowerBound && t <= view.upperBound {
                let h = 3 + 5 * share
                var p = Path()
                p.move(to: CGPoint(x: x(t), y: plot.maxY))
                p.addLine(to: CGPoint(x: x(t), y: plot.maxY - h))
                plotCtx.stroke(p, with: .color(Palette.danger), style: StrokeStyle(lineWidth: 2, lineCap: .round))
            }
        }
        // The crosshair: a guide at the pointer and a dot at each series' nearest point.
        if let t = hoverAt {
            let xx = x(t)
            var p = Path()
            p.move(to: CGPoint(x: xx, y: plot.minY))
            p.addLine(to: CGPoint(x: xx, y: plot.maxY))
            plotCtx.stroke(p, with: .color(Palette.textMuted.opacity(0.7)), style: StrokeStyle(lineWidth: 1, dash: [4, 3]))

        }
    }

    /// One series: runs split on failed buckets and gaps, stepped tails, the measured line faded and a
    /// median trend over it while points are denser than the eye follows (Android `drawSeries`).
    private func drawSeries(_ s: ChartSeries, in plotCtx: inout GraphicsContext, plot: CGRect,
                            x: (Date) -> CGFloat, y: (Double) -> CGFloat,
                            joinMs: Double, stepTailMs: Double, trend: Int, dense: Double, dots: Bool) {
        var runs: [[(Date, Double)]] = [[]]
        var prev: Date?
        for (t, v) in s.points {
            if let prev, t.timeIntervalSince(prev) * 1000 > joinMs, !(runs.last?.isEmpty ?? true) { runs.append([]) }
            if v == nil { if !(runs.last?.isEmpty ?? true) { runs.append([]) } }
            else { runs[runs.count - 1].append((t, v!)) }
            prev = t
        }
        for run in runs where !run.isEmpty {
            var line = Path()
            for (i, (t, v)) in run.enumerated() {
                let pt = CGPoint(x: x(t), y: y(v))
                if i == 0 { line.move(to: pt) }
                else {
                    if stepped { line.addLine(to: CGPoint(x: pt.x, y: y(run[i - 1].1))) }
                    line.addLine(to: pt)
                }
            }
            // A step's last value (before a gap, or a lone scan) still gets a short bar.
            if stepped { line.addLine(to: CGPoint(x: x(run.last!.0.addingTimeInterval(stepTailMs / 1000)), y: y(run.last!.1))) }
            if s.fill, run.count > 1 || stepped {
                var area = line
                area.addLine(to: CGPoint(x: x(stepped ? run.last!.0.addingTimeInterval(stepTailMs / 1000) : run.last!.0), y: plot.maxY))
                area.addLine(to: CGPoint(x: x(run.first!.0), y: plot.maxY))
                area.closeSubpath()
                plotCtx.fill(area, with: .color(s.color.opacity(s.fillOpacity)))
            }
            if s.halo { plotCtx.stroke(line, with: .color(s.color.opacity(0.25)), style: StrokeStyle(lineWidth: 6, lineCap: .round, lineJoin: .round)) }
            plotCtx.stroke(line, with: .color(s.color.opacity(1 - 0.7 * dense)),
                           style: StrokeStyle(lineWidth: max(0.8, s.lineWidth * (1 - 0.5 * dense)), lineCap: .round, lineJoin: .round))
            var probePoints = run.map { CGPoint(x: x($0.0), y: y($0.1)) }
            if trend > 1, dense > 0 {
                // A line through the medians of runs of [trend] points.
                let medians = ChartProbe.medianTrend(
                    run.map { CGPoint(x: $0.0.timeIntervalSince1970, y: $0.1) }, chunkSize: trend
                ).map { CGPoint(x: x(Date(timeIntervalSince1970: $0.x)), y: y($0.y)) }
                probePoints = medians
                if medians.count == 1 {
                    plotCtx.fill(Path(ellipseIn: CGRect(x: medians[0].x - 2.5, y: medians[0].y - 2.5, width: 5, height: 5)),
                                 with: .color(s.color.opacity(dense)))
                } else {
                    var trendLine = Path()
                    trendLine.addLines(medians)
                    plotCtx.stroke(trendLine, with: .color(s.color.opacity(dense)),
                                   style: StrokeStyle(lineWidth: 2.2, lineCap: .round, lineJoin: .round))
                }
                drawProbe(probePoints, at: hoverAt.map(x), color: s.color, in: &plotCtx, tail: 0)
                continue
            }
            drawProbe(probePoints, at: hoverAt.map(x), color: s.color, in: &plotCtx, tail: stepped ? x(run.last!.0.addingTimeInterval(stepTailMs / 1000)) - x(run.last!.0) : 0)
            if run.count == 1 && !stepped || dots {
                for (t, v) in run {
                    let c = CGPoint(x: x(t), y: y(v))
                    plotCtx.fill(Path(ellipseIn: CGRect(x: c.x - 4, y: c.y - 4, width: 8, height: 8)), with: .color(Palette.background))
                    plotCtx.fill(Path(ellipseIn: CGRect(x: c.x - 3, y: c.y - 3, width: 6, height: 6)), with: .color(s.color))
                }
            }
        }
    }

    private func drawProbe(_ points: [CGPoint], at x: CGFloat?, color: Color,
                           in ctx: inout GraphicsContext, tail: CGFloat) {
        guard let x, let p = ChartProbe.point(at: x, on: points, stepped: stepped, tail: tail) else { return }
        ctx.fill(Path(ellipseIn: CGRect(x: p.x - 2.5, y: p.y - 2.5, width: 5, height: 5)), with: .color(color))
    }

    /// Time ticks for the visible window (Android `timeTicks`): the smallest round step that gives at
    /// most five marks, aligned to local clock time (whole hours, local midnight).
    private func timeTicks() -> (TimeInterval, [Date]) {
        let view = visibleTime
        let span = max(view.upperBound.timeIntervalSince(view.lowerBound), 1)
        let steps: [TimeInterval] = [60, 120, 300, 600, 900, 1800, 3600, 2 * 3600, 3 * 3600, 6 * 3600, 12 * 3600,
                                    86_400, 2 * 86_400, 7 * 86_400, 14 * 86_400, 30 * 86_400, 91 * 86_400, 182 * 86_400, 365 * 86_400]
        let step = steps.first { span / $0 <= 4 } ?? 365 * 86_400
        let offset = Double(Calendar.current.timeZone.secondsFromGMT(for: view.lowerBound))
        var t = Date(timeIntervalSince1970: ((view.lowerBound.timeIntervalSince1970 + offset) / step).rounded(.up) * step - offset)
        var out: [Date] = []
        while t <= view.upperBound { out.append(t); t = t.addingTimeInterval(step) }
        return (step, out)
    }
}

/// Dots with names for the series; boxes for the state bands present (Android's legend).
private struct Legend: View {
    var series: [ChartSeries]
    var bands: [PhaseSpan]
    /// The chart's window: only bands covering it get an entry (Android's coverage check), and the
    /// unshaded background is explained with `clearLabel` when the chart supplies one.
    var from: Date = .distantPast
    var to: Date = .distantFuture
    var clearLabel: String? = nil

    /// The moment under the pointer and "name: value" for each named series.
    struct Probe {
        var time: String
        var values: [(color: Color, text: String)]
    }

    var body: some View {
        FlowCells(spacing: 12) {
            // By index, not by name: two series can share a name (same-named servers).
            ForEach(Array(series.enumerated()), id: \.offset) { _, s in
                HStack(spacing: 5) {
                    Circle().fill(s.color).frame(width: 7, height: 7)
                    Text(s.name).font(Fonts.body(11)).foregroundStyle(Palette.textSecondary).textSelection(.enabled)
                }
            }
            if let clearLabel {
                // The unshaded background — the normal state (Android `clearLabel`, first of the band entries).
                HStack(spacing: 5) {
                    RoundedRectangle(cornerRadius: 2).fill(Palette.background)
                        .overlay(RoundedRectangle(cornerRadius: 2).stroke(Palette.outline, lineWidth: 0.5))
                        .frame(width: 11, height: 8)
                    Text(clearLabel).font(Fonts.body(11)).foregroundStyle(Palette.textSecondary).textSelection(.enabled)
                }
            }
            ForEach(Array(bandEntries.enumerated()), id: \.offset) { _, e in
                HStack(spacing: 5) {
                    if e.eco {
                        // The eco hatch the band draws on the plot, as a legend swatch.
                        Canvas { ctx, size in
                            var p = Path()
                            p.move(to: CGPoint(x: 0, y: size.height))
                            p.addLine(to: CGPoint(x: size.width, y: 0))
                            p.move(to: CGPoint(x: size.width * 0.5, y: size.height))
                            p.addLine(to: CGPoint(x: size.width, y: size.height * 0.5))
                            p.move(to: CGPoint(x: 0, y: size.height * 0.5))
                            p.addLine(to: CGPoint(x: size.width * 0.5, y: 0))
                            ctx.stroke(p, with: .color(Palette.accentStrong.opacity(0.7)), lineWidth: 1)
                        }.frame(width: 11, height: 8)
                    } else {
                        RoundedRectangle(cornerRadius: 2).fill(bandColor(e.phase) ?? .clear).frame(width: 11, height: 8)
                    }
                    Text(e.name).font(Fonts.body(11)).foregroundStyle(Palette.textSecondary).textSelection(.enabled)
                }
            }
        }
    }

    /// One entry per band kind that covers a visible share of the window — Android keeps a band out
    /// of the legend under 1/400 of it — each phase once, and eco hatching once.
    private var bandEntries: [(phase: Phase, eco: Bool, name: String)] {
        var seen: [String] = []
        var out: [(Phase, Bool, String)] = []
        let whole = max(to.timeIntervalSince(from), 1)
        for b in bands where min(b.to, to).timeIntervalSince(max(b.from, from)) > whole / 400 {
            if b.eco {
                if !seen.contains("eco") { seen.append("eco"); out.append((b.phase, true, L("band_eco"))) }
            } else {
                let key = String(describing: b.phase), name = bandName(b.phase)
                if bandColor(b.phase) != nil, !name.isEmpty, !seen.contains(key) {
                    seen.append(key); out.append((b.phase, false, name))
                }
            }
        }
        return out
    }

    private func bandName(_ p: Phase) -> String {
        switch p {
        case .off: L("band_off")
        case .direct: L("band_direct")
        case .paused: L("band_paused")
        case .noNetwork: L("band_no_network")
        case .limited: L("band_limited")
        default: ""
        }
    }
}

/// The crosshair's readout (Android's cursor header): the moment and "name: value" for each named
/// series, drawn over the legend so hovering never changes the plot's height.
private struct ProbeRow: View {
    var probe: Legend.Probe

    var body: some View {
        FlowCells(spacing: 12) {
            Text(probe.time).font(Fonts.body(11, bold: true)).foregroundStyle(Palette.text).textSelection(.enabled)
            ForEach(probe.values.indices, id: \.self) { i in
                HStack(spacing: 5) {
                    Circle().fill(probe.values[i].color).frame(width: 7, height: 7)
                    Text(probe.values[i].text).font(Fonts.body(11)).foregroundStyle(Palette.textSecondary).textSelection(.enabled)
                }
            }
        }
    }
}

/// One horizontal bar: what the connection was doing over the period (Android `StateTimeline`); off stays empty.
struct StateStrip: View {
    var spans: [PhaseSpan]
    var from: Date
    var to: Date
    /// The service's first record, if known: the time before it is left bare — unknown, not "off".
    var dataStart: Date? = nil

    var body: some View {
        VStack(spacing: 4) {
            Canvas { ctx, size in
                let span = max(to.timeIntervalSince(from), 1)
                if let dataStart, dataStart > from {
                    let x1 = CGFloat(min(dataStart, to).timeIntervalSince(from) / span) * size.width
                    ctx.fill(Path(CGRect(x: 0, y: 0, width: x1, height: size.height)), with: .color(Palette.background))
                }
                for s in spans where s.phase != .off {
                    let x0 = CGFloat(s.from.timeIntervalSince(from) / span) * size.width
                    let x1 = CGFloat(s.to.timeIntervalSince(from) / span) * size.width
                    ctx.fill(Path(CGRect(x: x0, y: 0, width: max(x1 - x0, 1), height: size.height)), with: .color(phaseColor(s.phase)))
                }
            }
            .frame(height: 10)
            .background(Palette.unavailable)
            .clipShape(RoundedRectangle(cornerRadius: 3))
            HStack {
                ForEach(0..<5) { i in
                    let t = from.addingTimeInterval(to.timeIntervalSince(from) * Double(i) / 4)
                    Text(to.timeIntervalSince(from) <= 86_400 ? t.formatted(.dateTime.hour(.twoDigits(amPM: .omitted)).minute(.twoDigits))
                         : t.formatted(.dateTime.day().month(.abbreviated)))
                        .font(Fonts.body(10)).foregroundStyle(Palette.textSecondary).textSelection(.enabled)
                    if i < 4 { Spacer() }
                }
            }
        }
    }
}
