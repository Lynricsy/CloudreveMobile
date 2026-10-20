import SwiftUI

/// “打开方式”：列出候选预览器（原生优先）与“用其他应用打开”，可勾选“始终使用此方式”（按扩展名记住）
struct ViewerChooserList: View {
    let fileName: String
    let candidates: [ViewerOption]
    /// 当前使用的预览器（切换时显示勾号）
    let current: String?
    /// 选中的预览器（nil = 用其他应用打开）与是否记住
    let onPick: (ViewerOption?, Bool) -> Void

    @State private var remember = false

    var body: some View {
        List {
            Section {
                ForEach(candidates) { option in
                    Button {
                        onPick(option, remember)
                    } label: {
                        HStack {
                            Label(option.label, systemImage: option.isNative ? "doc.viewfinder" : "globe")
                            Spacer()
                            if option.id == current {
                                Image(systemName: "checkmark")
                                    .accessibilityHidden(true)
                            }
                        }
                    }
                    .accessibilityIdentifier("viewer.option." + option.id)
                }
                Button {
                    onPick(nil, false)
                } label: {
                    Label("用其他应用打开", systemImage: "square.and.arrow.up")
                }
                .accessibilityIdentifier("viewer.option.external")
            } header: {
                Text("选择打开“\(fileName)”的方式")
            }
            Section {
                Toggle("始终使用此方式打开此类文件", isOn: $remember)
                    .accessibilityIdentifier("viewer.remember")
            }
        }
    }
}
