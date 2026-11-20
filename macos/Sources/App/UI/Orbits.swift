import SwiftUI

/// One server of the scan in progress on the orbits (Android `Satellite`): it appears when its probe starts, turns into
/// a coloured planet at a height given by its ping (faster = closer) when it answers, and fades away if it doesn't.
struct Satellite: Equatable {
    let id: String
    let probing: Bool
    let ms: Int?
    let failed: Bool
}

/// The orbits' measures: Android's (`MainScreen.kt`, in dp) scaled to the Mac button, whose fill is 117 pt against
/// Android's 148 dp.
enum Orbit {
    static let f: CGFloat = 117.0 / 148.0
    /// Room for the orbits around the button.
    static let box: CGFloat = 268 * f
    /// Where planets start: the button's edge.
    static let base: CGFloat = 74 * f
    /// The rings: fastest answer of the scan on the inner one, slowest on the outer.
    static let fast: CGFloat = 100 * f
    static let slow: CGFloat = 122 * f
    /// A probe waits for its answer between them.
    static let waiting: CGFloat = 111 * f
    static let halo: CGFloat = 9 * f
    /// Corridor for planet centres: the halo never touches the button's ring (+ a gap) nor leaves the box.
    static let minR: CGFloat = (74 + 8 + 4) * f + halo
    static let maxR: CGFloat = box / 2 - halo
    /// Most planets at once: when more answer, the slowest one flies off to make room.
    static let maxPlanets = 22
    /// Servers of a scan passed to the orbits.
    static let maxSatellites = 80
    static let goldenAngle = 137.508
}

/// Animation state of one satellite, advanced while drawing. Each one has a character of its own (speed, a slow radial
/// wobble), seeded by its id: planets with the same ping would otherwise circle in lockstep, in pairs, forever.
private final class SatMotion {
    var angle = 0.0
    let born: Double
    let speed: Double
    private let wobbleAmp: Double
    private let wobblePeriod: Double
    private let wobblePhase: Double
    /// Small random offset of the starting angle, so newcomers don't form a perfect pattern either.
    let jitter: Double
    var last: Double
    var ms: Int?
    var failedAt = 0.0
    /// Pushed out to keep at most [Orbit.maxPlanets]: flies off like a failed one, but in its colour.
    var evictedAt = 0.0
    var radius: CGFloat = 0
    var gone: Bool { failedAt != 0 || evictedAt != 0 }

    init(id: String, born: Double) {
        var rnd = SplitMix(seed: id.utf8.reduce(UInt64(1469598103934665603)) { ($0 ^ UInt64($1)) &* 1099511628211 })
        speed = 0.78 + rnd.unit() * 0.5
        wobbleAmp = 1.5 + rnd.unit() * 2.5
        wobblePeriod = 3 + rnd.unit() * 4
        wobblePhase = rnd.unit() * 2 * .pi
        jitter = (rnd.unit() - 0.5) * 50
        self.born = born
        last = born
    }

    /// Radial breathing in points at time [t].
    func wobble(_ t: Double) -> CGFloat { CGFloat(wobbleAmp * sin(t / wobblePeriod * 2 * .pi + wobblePhase)) * Orbit.f }
}

/// A small seeded generator (the same id gives the same character on every run).
private struct SplitMix {
    var state: UInt64
    init(seed: UInt64) { state = seed }
    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
    mutating func unit() -> Double { Double(next() >> 11) / Double(1 << 53) }
}

/// What the orbits remember between frames: the satellites, this scan's scale and the slide in / out.
private final class OrbitSky {
    /// The one sky, held outside the `Orbits` view: the main section's view is dropped while another section is up
    /// (`Shell`'s switch), and a `@State` sky would die with it — on return mid-scan every planet would be reborn and
    /// refly to its orbit. There is only the connect button's `Orbits`, only ever drawn on the main thread.
    nonisolated(unsafe) static let shared = OrbitSky()

    var motions: [String: SatMotion] = [:]
    var order: [String] = []
    var scanId: UUID?
    /// Fastest and slowest answer of this sky (the rings' labels); kept until the orbits close.
    var lo = 0, hi = 0
    var from: CGFloat = 0, target: CGFloat = 0, changedAt = 0.0, duration = 0.8

    /// How far the orbits are out from under the button, 0…1 (ease out, like Android's FastOutSlowIn).
    func appear(visible: Bool, now: Double) -> CGFloat {
        let current = value(now)
        if (target == 1) != visible {
            from = current
            target = visible ? 1 : 0
            changedAt = now
            duration = visible ? 0.8 : 0.55
        }
        return value(now)
    }

    private func value(_ now: Double) -> CGFloat {
        let p = min(max((now - changedAt) / duration, 0), 1)
        let e = 1 - pow(1 - p, 3)
        return from + (target - from) * CGFloat(e)
    }

    func sync(_ sats: [Satellite], scanId: UUID?, now: Double) {
        // Each scan starts a fresh sky; after it ends the last one stays until the orbits are drawn in.
        if let scanId, scanId != self.scanId {
            self.scanId = scanId
            motions.removeAll()
            order.removeAll()
            lo = 0
            hi = 0
        }
        for sat in sats {
            let m: SatMotion
            if let known = motions[sat.id] {
                m = known
            } else {
                // Each newcomer takes roughly the golden angle after the previous one: never on top of another.
                m = SatMotion(id: sat.id, born: now)
                m.angle = (Double(motions.count) * Orbit.goldenAngle + m.jitter + 360).truncatingRemainder(dividingBy: 360)
                motions[sat.id] = m
                order.append(sat.id)
            }
            if let ms = sat.ms, m.ms == nil { m.ms = ms }
            if sat.failed, m.failedAt == 0 { m.failedAt = now }
        }
        // A probe that ended without a result (the scan stopped) drops out of the list: it flies off.
        let listed = Set(sats.map(\.id))
        for (id, m) in motions where !listed.contains(id) && m.ms == nil && !m.gone { m.failedAt = now }
        // Keep the sky readable: over the limit, the slowest answered planets leave (probes in flight stay).
        let present = motions.values.filter { !$0.gone }
        if present.count > Orbit.maxPlanets {
            present.filter { $0.ms != nil }.sorted { ($0.ms ?? 0) > ($1.ms ?? 0) }
                .prefix(present.count - Orbit.maxPlanets).forEach { $0.evictedAt = now }
        }
    }
}

/// Two orbits of servers around the button while they are checked: a live map of the scan. Probes in flight are hollow
/// rings launched from the button; an answer lights a planet in its ping's colour and sends it to its height between the
/// fastest (inner ring) and the slowest answer (outer ring), on a log scale; no reply fades away. Inner orbits turn
/// faster (as Kepler's), and planets passing on nearly the same orbit are pushed apart. The rings carry callouts with
/// the two pings. Computed from the clock while drawing (Android: the same in the draw phase).
struct Orbits: View {
    var sats: [Satellite]
    var scanId: UUID?
    /// Out from under the button (else drawn in).
    var visible: Bool

    /// The sky outlives this view (`OrbitSky.shared`): section switches drop the view, they don't restart the scan's
    /// planets.
    private let sky = OrbitSky.shared

    var body: some View {
        // Unpaused while mounted: the slide back under the button still needs frames after `visible`
        // goes false (the linger keeps the view alive exactly for that).
        TimelineView(.animation) { timeline in
            Canvas { ctx, size in
                draw(&ctx, size: size, now: timeline.date.timeIntervalSinceReferenceDate)
            }
        }
        // Wider than the box: the callouts reach past the outer ring.
        .frame(width: 320, height: Orbit.box + 40)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    private func lerp(_ a: CGFloat, _ b: CGFloat, _ k: CGFloat) -> CGFloat { a + (b - a) * k }

    private func draw(_ ctx: inout GraphicsContext, size: CGSize, now t: Double) {
        sky.sync(sats, scanId: scanId, now: t)
        let k = sky.appear(visible: visible, now: t)
        guard k > 0.001 else { return }
        let c = CGPoint(x: size.width / 2, y: size.height / 2)
        let base = Orbit.base, fast = Orbit.fast, slow = Orbit.slow, waiting = Orbit.waiting
        func circle(_ p: CGPoint, _ r: CGFloat) -> Path { Path(ellipseIn: CGRect(x: p.x - r, y: p.y - r, width: 2 * r, height: 2 * r)) }

        let answered = sky.motions.values.filter { !$0.gone }.compactMap(\.ms)
        if let lo = answered.min(), let hi = answered.max() { sky.lo = lo; sky.hi = hi }
        let lo = sky.lo > 0 ? sky.lo : nil
        let hi = sky.hi > 0 ? sky.hi : nil
        let ringStyle = StrokeStyle(lineWidth: 1, dash: [3, 4])
        ctx.stroke(circle(c, lerp(base, fast, k)), with: .color(Palette.outline.opacity(0.7 * k)), style: ringStyle)
        ctx.stroke(circle(c, lerp(base, slow, k)), with: .color(Palette.outline.opacity(0.7 * k)), style: ringStyle)

        // Drawing-style callout: a dot on the ring, a slanted line out past the orbits, a shelf and the label.
        func label(_ text: String, radius: CGFloat, angle: Double, toLeft: Bool) {
            let a = angle * .pi / 180
            let dir = CGPoint(x: cos(a), y: sin(a))
            let anchor = CGPoint(x: c.x + dir.x * radius, y: c.y + dir.y * radius)
            let out = lerp(base, slow, k) + 10 * Orbit.f
            let elbow = CGPoint(x: c.x + dir.x * out, y: c.y + dir.y * out)
            let shelfEnd = CGPoint(x: elbow.x + (toLeft ? -1 : 1) * 22 * Orbit.f, y: elbow.y)
            let line = GraphicsContext.Shading.color(Palette.textMuted.opacity(0.8 * k))
            ctx.fill(circle(anchor, 1.6), with: line)
            var p = Path()
            p.move(to: anchor)
            p.addLine(to: elbow)
            p.addLine(to: shelfEnd)
            ctx.stroke(p, with: line, lineWidth: 0.8)
            let resolved = ctx.resolve(Text(text).font(Fonts.display(10)).foregroundStyle(Palette.textSecondary.opacity(k)))
            ctx.draw(resolved, at: CGPoint(x: shelfEnd.x + (toLeft ? -3 : 3), y: shelfEnd.y), anchor: toLeft ? .trailing : .leading)
        }
        // Inner ring (fastest): bottom left; outer ring (slowest): top right.
        if let lo { label(L("ms", lo), radius: lerp(base, fast, k), angle: 135, toLeft: true) }
        if let hi, hi != lo { label(L("ms", hi), radius: lerp(base, slow, k), angle: -45, toLeft: false) }

        func progress(_ from: Double, _ duration: Double) -> CGFloat {
            let p = CGFloat(min(max((t - from) / duration, 0), 1))
            return 1 - (1 - p) * (1 - p)
        }
        // Planets on nearly the same orbit would overlap while one overtakes the other: close pairs are pushed apart
        // radially (a display offset, recomputed every frame, so they settle back after passing).
        let live = sky.order.compactMap { sky.motions[$0] }.filter { !$0.gone && $0.radius > 0 }
        var push: [ObjectIdentifier: CGFloat] = [:]
        let minGap = 19 * Orbit.f
        for i in live.indices {
            for j in (i + 1)..<live.count {
                let p = live[i], q = live[j]
                let da = ((p.angle - q.angle + 540).truncatingRemainder(dividingBy: 360) - 180) * .pi / 180
                let along = CGFloat(abs(da)) * (p.radius + q.radius) / 2
                let across = q.radius - p.radius
                let dist = (along * along + across * across).squareRoot()
                if dist < minGap {
                    let f = (minGap - dist) / 2 * min(max(1 - along / minGap, 0), 1)
                    let sign: CGFloat = across >= 0 ? 1 : -1
                    push[ObjectIdentifier(q), default: 0] += f * sign
                    push[ObjectIdentifier(p), default: 0] -= f * sign
                }
            }
        }
        for id in sky.order {
            guard let m = sky.motions[id] else { continue }
            let gone: CGFloat = m.failedAt > 0 ? progress(m.failedAt, 0.9) : m.evictedAt > 0 ? progress(m.evictedAt, 0.9) : 0
            if gone >= 1 { continue }
            // Height: launched from the button to the middle orbit, then to its ping's place between the fastest and
            // the slowest answer so far, on a log scale.
            let target: CGFloat? = m.ms.map { ms in
                guard let lo, let hi, hi > lo else { return (fast + slow) / 2 }
                let x = (log(Double(ms)) - log(Double(lo))) / (log(Double(hi)) - log(Double(lo)))
                return fast + (slow - fast) * CGFloat(min(max(x, 0), 1))
            }
            let dt = min(max(t - m.last, 0), 0.1)
            if let target {
                // Joined the sky with its answer already in (the screen was away when it came): appear on the orbit,
                // don't fly out of the button.
                if m.radius == 0 { m.radius = target }
                // Glide towards the target; it moves when a new fastest / slowest answer arrives.
                m.radius += (target - m.radius) * CGFloat(1 - exp(-6 * dt))
            } else {
                m.radius = lerp(base, waiting, progress(m.born, 0.6))
            }
            let maxPush = 6 * Orbit.f
            let nudge = min(max(push[ObjectIdentifier(m)] ?? 0, -maxPush), maxPush) + (m.ms != nil ? m.wobble(t) : 0)
            var placed = m.radius + nudge + 14 * Orbit.f * gone
            // While still launching (under the button) only the outer limit applies.
            placed = m.radius >= Orbit.minR ? min(max(placed, Orbit.minR), Orbit.maxR) : min(placed, Orbit.maxR)
            let r = lerp(base, placed, k)
            // Inner orbits turn faster (Kepler-like), so the picture keeps moving.
            m.last = t
            let ratio = Double(waiting / max(r, 1))
            m.angle = (m.angle + 24 * m.speed * ratio * ratio.squareRoot() * dt).truncatingRemainder(dividingBy: 360)
            let a = m.angle * .pi / 180
            let p = CGPoint(x: c.x + cos(a) * r, y: c.y + sin(a) * r)
            let alpha = Double(k * (1 - gone))
            let grow = progress(m.born, 0.4)
            if m.failedAt > 0 {
                ctx.fill(circle(p, 3.5 * Orbit.f * (1 - gone * 0.6)), with: .color(Palette.unavailableMark.opacity(0.7 * alpha)))
            } else if let ms = m.ms {
                let color = Palette.ping(ms)
                ctx.fill(circle(p, Orbit.halo), with: .color(color.opacity(0.35 * alpha)))
                ctx.fill(circle(p, 6 * Orbit.f), with: .color(color.opacity(alpha)))
            } else {
                ctx.fill(circle(p, 5 * Orbit.f * grow), with: .color(Palette.background.opacity(alpha)))
                ctx.stroke(circle(p, 5 * Orbit.f * grow), with: .color(Palette.textMuted.opacity(alpha)), lineWidth: 1.5 * Orbit.f)
            }
        }
    }
}
