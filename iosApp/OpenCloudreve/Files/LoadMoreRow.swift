import SwiftUI

/// 分页尾行：出现在屏幕上即请求下一页
struct LoadMoreRow: View {
    let loading: Bool
    let loadMore: () -> Void

    var body: some View {
        HStack {
            Spacer()
            ProgressView()
                .accessibilityLabel(Text("正在加载更多"))
            Spacer()
        }
        .padding(.vertical, 8)
        .onAppear {
            if !loading { loadMore() }
        }
    }
}
