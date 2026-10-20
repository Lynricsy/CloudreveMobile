import SwiftUI
import OpenCloudreveCore

/// 用户存储：已用 / 总量，校准（重新计算已用空间）
struct AdminUserStorageSection: View {
    let used: Int64
    let capacity: Capacity?
    let calibrating: Bool
    let calibrate: () -> Void

    var body: some View {
        Section {
            LabeledContent("已用空间") {
                Text(verbatim: SettingsFormat.bytes(capacity?.used ?? used))
            }
            if let capacity {
                LabeledContent("总容量") {
                    Text(verbatim: SettingsFormat.bytes(capacity.total))
                }
            }
            Button(action: calibrate) {
                HStack {
                    Label("重新计算已用空间", systemImage: "arrow.triangle.2.circlepath")
                    Spacer()
                    if calibrating {
                        ProgressView()
                    }
                }
            }
            .disabled(calibrating)
        } header: {
            Text("存储")
        } footer: {
            Text("已用空间与实际文件不一致时，可重新计算。")
        }
    }
}
