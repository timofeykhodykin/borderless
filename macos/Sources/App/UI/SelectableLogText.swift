import AppKit
import SwiftUI

/// One text storage for the entire document: drag selection and Cmd+A work across every line.
struct SelectableLogText: NSViewRepresentable {
    var text: String
    var selectAll = 0
    var attributedText: NSAttributedString? = nil

    final class Coordinator {
        var selectionRequest = 0
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeNSView(context: Context) -> NSScrollView {
        let scroll = TextScrollView()
        scroll.hasVerticalScroller = true
        scroll.drawsBackground = false
        scroll.autohidesScrollers = true
        let view = LogTextView()
        view.isEditable = false
        view.isSelectable = true
        view.isRichText = true
        view.importsGraphics = false
        view.allowsUndo = false
        view.usesFindBar = true
        view.drawsBackground = false
        view.font = .monospacedSystemFont(ofSize: 11.5, weight: .regular)
        view.textColor = NSColor(Palette.text)
        view.textContainerInset = NSSize(width: 20, height: 12)
        view.minSize = .zero
        view.maxSize = NSSize(width: CGFloat.greatestFiniteMagnitude, height: CGFloat.greatestFiniteMagnitude)
        view.isVerticallyResizable = true
        view.isHorizontallyResizable = false
        view.autoresizingMask = [.width]
        view.textContainer?.widthTracksTextView = true
        view.textContainer?.lineFragmentPadding = 0
        scroll.documentView = view
        return scroll
    }

    /// Paint rounded source badges behind real text, so their labels remain selectable and copyable.
    final class LogTextView: NSTextView {
        override func draw(_ dirtyRect: NSRect) {
            if let layout = layoutManager, let container = textContainer, let storage = textStorage {
                let origin = textContainerOrigin
                let local = dirtyRect.offsetBy(dx: -origin.x, dy: -origin.y)
                let glyphs = layout.glyphRange(forBoundingRect: local, in: container)
                let characters = layout.characterRange(forGlyphRange: glyphs, actualGlyphRange: nil)
                storage.enumerateAttribute(LogDocument.badgeColor, in: characters) { value, range, _ in
                    guard let color = value as? NSColor else { return }
                    let badgeGlyphs = layout.glyphRange(forCharacterRange: range, actualCharacterRange: nil)
                    let rect = layout.boundingRect(forGlyphRange: badgeGlyphs, in: container)
                        .offsetBy(dx: origin.x, dy: origin.y).insetBy(dx: 1, dy: -1)
                    color.setFill()
                    NSBezierPath(roundedRect: rect, xRadius: 3, yRadius: 3).fill()
                }
            }
            super.draw(dirtyRect)
        }
    }

    final class TextScrollView: NSScrollView {
        override func layout() {
            super.layout()
            guard let view = documentView as? NSTextView else { return }
            let width = max(contentSize.width, 1)
            if view.frame.width != width { view.setFrameSize(NSSize(width: width, height: max(view.frame.height, contentSize.height))) }
            view.minSize = NSSize(width: 0, height: contentSize.height)
        }
    }

    func updateNSView(_ scroll: NSScrollView, context: Context) {
        guard let view = scroll.documentView as? NSTextView else { return }
        let document = attributedText ?? NSAttributedString(string: text, attributes: [
            .font: NSFont.monospacedSystemFont(ofSize: 11.5, weight: .regular), .foregroundColor: NSColor(Palette.text)
        ])
        if view.textStorage?.isEqual(to: document) != true {
            let ranges = view.selectedRanges
            let origin = scroll.contentView.bounds.origin
            let oldString = view.string
            let prefix = document.string.hasSuffix(oldString) ? max(document.length - (oldString as NSString).length, 0) : 0
            view.textStorage?.setAttributedString(document)
            let length = document.length
            view.selectedRanges = ranges.map {
                let range = $0.rangeValue
                let start = min(range.location + (range.length > 0 ? prefix : 0), length)
                return NSValue(range: NSRange(location: start, length: min(range.length, length - start)))
            }
            scroll.contentView.scroll(to: origin)
        }
        if context.coordinator.selectionRequest != selectAll {
            context.coordinator.selectionRequest = selectAll
            view.window?.makeFirstResponder(view)
            view.selectAll(nil)
        }
    }
}
