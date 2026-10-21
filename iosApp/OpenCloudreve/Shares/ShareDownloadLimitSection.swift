import SwiftUI
import OpenCloudreveCore

/// 下载次数（仅文件分享）：不限 / 预设 / 自定义数字。
/// 选项保存在本地：输入自定义次数时即使等于某个预设也保持“自定义”，输入框不会消失。
struct ShareDownloadLimitSection: View {
    @Bindable var host: ShareEditHost
    @State private var selection: ShareDownloadChoice

    init(host: ShareEditHost) {
        _host = Bindable(host)
        // 表单在加载完成后才出现，这里取一次当前值作为初始选项
        _selection = State(initialValue: ShareDownloadChoice(limit: host.state.downloadLimit?.int32Value, presets: Self.presets))
    }

    var body: some View {
        Section {
            Picker("下载次数", selection: $selection) {
                Text("不限").tag(ShareDownloadChoice.unlimited)
                ForEach(Self.presets, id: \.self) { count in
                    Text("\(Int(count)) 次", comment: "分享下载次数预设").tag(ShareDownloadChoice.preset(count))
                }
                Text("自定义").tag(ShareDownloadChoice.custom)
            }
            .accessibilityIdentifier("shareEdit.downloads")
            if selection == .custom {
                TextField("次数", text: $host.downloadLimitText, prompt: Text("输入下载次数"))
                    .keyboardType(.numberPad)
            }
        } footer: {
            if !host.state.downloadLimitValid {
                Text("下载次数须大于 0")
                    .foregroundStyle(.red)
            } else if selection != .unlimited {
                Text("达到次数后分享链接自动失效。")
            }
        }
        .onChange(of: selection) { _, choice in
            apply(choice)
        }
    }

    private func apply(_ choice: ShareDownloadChoice) {
        switch choice {
        case .unlimited:
            host.send(ShareEditIntentSetDownloadLimit(limit: nil))
        case let .preset(count):
            host.send(ShareEditIntentSetDownloadLimit(limit: KotlinInt(int: count)))
        case .custom:
            // 保留当前次数作为输入框初值；原来不限则记为 0，填入有效次数前不能提交
            if host.state.downloadLimit == nil {
                host.send(ShareEditIntentSetDownloadLimit(limit: KotlinInt(int: 0)))
            }
        }
    }

    private static var presets: [Int32] {
        ShareEditState.companion.DOWNLOAD_PRESETS.map(\.int32Value)
    }
}
