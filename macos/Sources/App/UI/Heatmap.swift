import SwiftUI

/// The map of servers (Android `Heatmap.kt`): a cell per server in its ping colour, each group on its own rows in the
/// user's order. Click a cell: the server is picked (selected in the list below, which scrolls to it — the Mac's form of
/// Android's info panel); a click toggles the pick; hover: name and ping; right click: the server's menu. Queued cells
/// are dimmed while a check runs, probing ones spin. The server in use and the picked one get a border inside the cell.
struct Heatmap: View {
    @Bindable var model: MainModel
    @Environment(MenuState.self) private var menus
    /// The grid's width, read once for the whole map (each cell used to measure itself for its own menu).
    @State private var width: CGFloat = 0

    private let cell: CGFloat = 18, gap: CGFloat = 4, groupGap: CGFloat = 7

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            VStack(alignment: .leading, spacing: groupGap) {
                ForEach(Array(model.grouped.enumerated()), id: \.offset) { _, servers in
                    FlowCells(spacing: gap) {
                        ForEach(servers) { s in Cell(model: model, s: s, side: cell) }
                    }
                }
            }
            .background(GeometryReader { g in Color.clear
                .onAppear { width = g.size.width }
                .onChange(of: g.size.width) { width = $1 } })
            // One catcher for the whole map: the cell under the click is worked out from the layout, not asked
            // of a per-cell NSView + GeometryReader (thousands of them on a subscription's list).
            .appContextMenu(menus, at: menu)
            Legend()
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: Dims.cardRadius).fill(Palette.card.opacity(0.55)))
        .overlay(RoundedRectangle(cornerRadius: Dims.cardRadius).strokeBorder(Palette.outline.opacity(0.7), lineWidth: 1))
    }

    /// The cell's server under the click (the point is in the grid's coordinates, top-left); empty = a miss,
    /// the menu doesn't open. The math is `FlowCells`' own: a new row per group, `columns` cells a row.
    private func menu(at p: CGPoint) -> [MenuItem] {
        guard width > 0 else { return [] }
        let columns = max(Int((width + gap) / (cell + gap)), 1)
        var top: CGFloat = 0
        for servers in model.grouped {
            let rows = (servers.count + columns - 1) / columns
            let h = CGFloat(rows) * cell + CGFloat(rows - 1) * gap
            if p.y < top + h {
                let row = Int(p.y - top) / Int(cell + gap), col = Int(p.x) / Int(cell + gap)
                // A click in a gap between cells, rows or past the last cell of a short row hits nothing.
                guard row < rows, col < columns,
                      p.x - CGFloat(col) * (cell + gap) <= cell,
                      p.y - top - CGFloat(row) * (cell + gap) <= cell else { return [] }
                let i = row * columns + col
                return i < servers.count ? serverMenu(model, servers[i], menus) : []
            }
            top += h + groupGap
        }
        return []
    }
}

/// One cell of the map: the server's colour and marks; click picks it, the next click lets it go.
private struct Cell: View {
    var model: MainModel
    var s: ServerItem
    var side: CGFloat

    var body: some View {
        let current = model.phase == .connected && s.id == model.currentId
        let picked = s.id == model.pickedId
        // A limited-network server is never probed: the cross would claim a failure nobody saw.
        let down = s.checked && s.ms == nil && !s.limited
        let probing = model.scan?.probing.contains(s.id) == true
        // `EngineStatus.pending`: still waiting for its probe in the scan — a stale colour dims until
        // its turn comes (the row's queued dim, the map's Android `pending`).
        let queued = model.pendingIds.contains(s.id)
        // Never checked reads as untested (the muted cell), not as the "no reply" grey — a silent
        // limited server isn't a failed one either.
        let untested = !s.checked
        let shape = RoundedRectangle(cornerRadius: 5)
        shape
            .fill(down ? Palette.unavailable : untested ? Palette.untested : Palette.ping(s.ms))
            .opacity(queued ? 0.45 : 1)
            .frame(width: side, height: side)
            .overlay(down ? CrossMark(color: Palette.unavailableMark).frame(width: side, height: side) : nil)
            .overlay(probing ? Spinner(lineWidth: 1.5).padding(4) : nil)
            // The limited-network tower the rows carry, on the cell too — silent and failed limited
            // cells keep it (Android's cell mark).
            .overlay(s.limited
                     ? Image(systemName: "antenna.radiowaves.left.and.right").font(.system(size: 8, weight: .bold))
                        .foregroundStyle(StateTone.limited.strong).padding(1) : nil)
            // As on Android: a 2 pt border inside the cell, so it never reaches the neighbours.
            .overlay(current ? shape.strokeBorder(Palette.accentStrong, lineWidth: 2)
                     : picked ? shape.strokeBorder(Palette.textSecondary, lineWidth: 2) : nil)
            .contentShape(shape)
            .help([model.displayName(s), pingText(s)].joined(separator: " · "))
            .onTapGesture {
                // Every click toggles, a fast run of them too (as the list's rows do, owner 2026-11-07).
                model.pickedId = model.pickedId == s.id ? nil : s.id
            }
    }
}

private struct Legend: View {
    var body: some View {
        HStack(spacing: 6) {
            Text(L("ms", Palette.pingFast)).font(Fonts.body(11)).foregroundStyle(Palette.textSecondary).textSelection(.enabled)
            Capsule().fill(LinearGradient(colors: Palette.pingLegend, startPoint: .leading, endPoint: .trailing))
                .frame(width: 64, height: 5)
            Text(L("legend_slow", Palette.pingSlow / 1000)).font(Fonts.body(11)).foregroundStyle(Palette.textSecondary).textSelection(.enabled)
            Spacer()
            RoundedRectangle(cornerRadius: 2.5).fill(Palette.unavailable).frame(width: 10, height: 10)
                .overlay(CrossMark(color: Palette.unavailableMark))
            Text(L("legend_down")).font(Fonts.body(11)).foregroundStyle(Palette.textSecondary).textSelection(.enabled)
        }
    }
}

/// Lays out its children left to right, wrapping whole children to new rows (cells, chips).
struct FlowCells: Layout {
    var spacing: CGFloat
    /// Between rows; the same as between items unless given.
    var lineSpacing: CGFloat? = nil

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? 400
        var x: CGFloat = 0, y: CGFloat = 0, rowH: CGFloat = 0
        for v in subviews {
            let s = v.sizeThatFits(.unspecified)
            if x > 0 && x + s.width > width { x = 0; y += rowH + (lineSpacing ?? spacing); rowH = 0 }
            x += s.width + spacing
            rowH = max(rowH, s.height)
        }
        return CGSize(width: width, height: y + rowH)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, rowH: CGFloat = 0
        for v in subviews {
            let s = v.sizeThatFits(.unspecified)
            if x > bounds.minX && x + s.width > bounds.maxX { x = bounds.minX; y += rowH + (lineSpacing ?? spacing); rowH = 0 }
            v.place(at: CGPoint(x: x, y: y), proposal: .unspecified)
            x += s.width + spacing
            rowH = max(rowH, s.height)
        }
    }
}
