import SwiftUI

/// "12 Oct, 09:00–18:30", or "12 Oct 09:00 – 14 Oct 18:30" across days (Android `formatRange`);
/// the year joins only when the range isn't wholly inside the current one.
func rangeText(_ r: ClosedRange<Date>) -> String {
    let now = Date()
    let thisYear = Calendar.current.isDate(r.lowerBound, equalTo: now, toGranularity: .year)
        && Calendar.current.isDate(r.upperBound, equalTo: now, toGranularity: .year)
    let day = thisYear ? Date.FormatStyle().day().month(.abbreviated)
                       : Date.FormatStyle().day().month(.abbreviated).year()
    let time = Date.FormatStyle().hour(.twoDigits(amPM: .omitted)).minute(.twoDigits)
    if Calendar.current.isDate(r.lowerBound, inSameDayAs: r.upperBound) {
        return "\(r.lowerBound.formatted(day)), \(r.lowerBound.formatted(time))–\(r.upperBound.formatted(time))"
    }
    return "\(r.lowerBound.formatted(day)) \(r.lowerBound.formatted(time)) – \(r.upperBound.formatted(day)) \(r.upperBound.formatted(time))"
}

/// A custom statistics period (Android `RangeDialog`): a date and a time for its start and its end, side by side —
/// a calendar each (up to today) and a time field.
struct RangeDialog: View {
    @State var from: Date
    @State var to: Date
    /// How far back statistics reach (the settings' statsDays); nil leaves the hint out.
    var statsDays: Int? = nil
    var apply: (ClosedRange<Date>) -> Void
    @Environment(MenuState.self) private var menus
    @Environment(\.snapshot) private var snapshot

    var body: some View {
        AppDialog(title: L("period_custom"), width: 540, spacing: 12) {
            HStack(alignment: .top, spacing: 20) {
                end(L("range_from"), $from)
                Hairline(vertical: true)
                end(L("range_to"), $to)
            }
            .fixedSize(horizontal: false, vertical: true)
            if from >= to {
                Text(L("range_invalid")).font(Fonts.body(12)).foregroundStyle(Palette.danger).textSelection(.enabled)
            }
            // A range beyond the retention only looks broken (empty figures): say how far records go.
            if let statsDays {
                Text(String(format: macText("mac.range.days", "Statistics go back %d days"), statsDays))
                    .font(Fonts.body(11)).foregroundStyle(Palette.textSecondary).textSelection(.enabled)
            }
            HStack(spacing: 6) {
                Text(from < to ? rangeText(from...to) : "").font(Fonts.body(12)).foregroundStyle(Palette.textSecondary).textSelection(.enabled)
                Spacer()
                MacButton(title: L("cancel")) { menus.closeDialog() }.keyboardShortcut(.cancelAction)
                MacButton(title: L("done"), accent: true) {
                    menus.closeDialog()
                    apply(from...to)
                }
                .disabled(from >= to)
                .keyboardShortcut(.defaultAction)
            }
        }
        .tint(Palette.accent)
    }

    private func end(_ title: String, _ value: Binding<Date>) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(title).font(Fonts.body(13, bold: true)).foregroundStyle(Palette.text).textSelection(.enabled)
                Spacer()
                if snapshot {
                    Text(value.wrappedValue.formatted(date: .omitted, time: .shortened)).modifier(FieldBezel())
                } else {
                    // Android's TimePicker writes SECOND=0/MILLISECOND=0 on commit — a picked time is
                    // whole minutes (a left-over second made an intended same-minute end impossible).
                    DatePicker("", selection: minuteOnly(value), displayedComponents: .hourAndMinute)
                        .labelsHidden().datePickerStyle(.field)
                }
            }
            if snapshot {
                Text(value.wrappedValue.formatted(date: .long, time: .omitted)).font(Fonts.body(12)).foregroundStyle(Palette.textSecondary)
                    .frame(width: 220, height: 160, alignment: .topLeading)
            } else {
                // Android's calendar allows up to now + 24 h (its `PastOrToday`), not only up to now.
                DatePicker("", selection: value, in: ...Date().addingTimeInterval(86_400), displayedComponents: .date)
                    .labelsHidden().datePickerStyle(.graphical)
                    .frame(width: 220)
            }
        }
    }

    /// [b] dropping seconds and fractions on every write — the field edits hour and minute only.
    private func minuteOnly(_ b: Binding<Date>) -> Binding<Date> {
        Binding(get: { b.wrappedValue },
                set: { b.wrappedValue = Calendar.current.date(
                    from: Calendar.current.dateComponents([.year, .month, .day, .hour, .minute], from: $0)) ?? $0 })
    }
}
