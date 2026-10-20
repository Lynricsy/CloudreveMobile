import SwiftUI
import UIKit

/// 纯文本编辑区：可切换等宽字体与自动换行（不换行时横向滚动），关闭自动更正/智能标点
struct CodeTextView: UIViewRepresentable {
    @Binding var text: String
    let editable: Bool
    let monospaced: Bool
    let wraps: Bool

    func makeCoordinator() -> Coordinator { Coordinator(text: $text) }

    func makeUIView(context: Context) -> UITextView {
        // TextKit 1：不换行时文本容器可无限宽，横向滚动可靠
        let view = UITextView(usingTextLayoutManager: false)
        view.delegate = context.coordinator
        view.autocorrectionType = .no
        view.autocapitalizationType = .none
        view.smartQuotesType = .no
        view.smartDashesType = .no
        view.smartInsertDeleteType = .no
        view.spellCheckingType = .no
        view.alwaysBounceVertical = true
        view.keyboardDismissMode = .interactive
        view.textContainerInset = UIEdgeInsets(top: 12, left: 8, bottom: 12, right: 8)
        view.backgroundColor = .systemBackground
        view.text = text
        context.coordinator.configure(view, editable: editable, monospaced: monospaced, wraps: wraps)
        return view
    }

    func updateUIView(_ view: UITextView, context: Context) {
        context.coordinator.text = $text
        if view.text != text { view.text = text }
        context.coordinator.configure(view, editable: editable, monospaced: monospaced, wraps: wraps)
    }

    @MainActor
    final class Coordinator: NSObject, UITextViewDelegate {
        var text: Binding<String>
        private var applied: (monospaced: Bool, wraps: Bool)?

        init(text: Binding<String>) {
            self.text = text
        }

        func textViewDidChange(_ textView: UITextView) {
            text.wrappedValue = textView.text
        }

        func configure(_ view: UITextView, editable: Bool, monospaced: Bool, wraps: Bool) {
            if view.isEditable != editable { view.isEditable = editable }
            if let applied, applied.monospaced == monospaced, applied.wraps == wraps { return }
            applied = (monospaced, wraps)
            let size = UIFont.preferredFont(forTextStyle: .body).pointSize
            view.font = monospaced ? .monospacedSystemFont(ofSize: size, weight: .regular) : .systemFont(ofSize: size)
            let container = view.textContainer
            if wraps {
                container.widthTracksTextView = true
                container.size = CGSize(width: view.bounds.width, height: .greatestFiniteMagnitude)
            } else {
                container.widthTracksTextView = false
                container.size = CGSize(width: CGFloat.greatestFiniteMagnitude, height: .greatestFiniteMagnitude)
            }
            view.showsHorizontalScrollIndicator = !wraps
            view.alwaysBounceHorizontal = !wraps
        }
    }
}
