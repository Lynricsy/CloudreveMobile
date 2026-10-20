import SwiftUI

/// 表单主操作按钮：通栏 `.glassProminent`，进行中显示进度圈。
/// 放在单独的 `Section` 里，去掉行背景让玻璃按钮直接浮在分组列表背景上。
struct PrimaryActionButton: View {
    let title: LocalizedStringKey
    var busy = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Group {
                if busy {
                    ProgressView()
                        .accessibilityLabel(Text(title))
                } else {
                    Text(title)
                }
            }
            .frame(maxWidth: .infinity, minHeight: 24)
        }
        .buttonStyle(.glassProminent)
        .controlSize(.large)
        .listRowBackground(Color.clear)
        .listRowInsets(EdgeInsets())
    }
}
