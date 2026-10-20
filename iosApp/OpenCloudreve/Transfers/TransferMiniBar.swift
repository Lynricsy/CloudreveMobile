import SwiftUI

/// Tab 栏上方的传输迷你栏（`.tabViewBottomAccessory`）：“上传 3 项 45%”，点按进入任务 Tab
struct TransferMiniBar: View {
    let uploads: Int
    let downloads: Int
    let progress: Double
    let open: () -> Void

    var body: some View {
        Button(action: open) {
            VStack(spacing: 4) {
                HStack(spacing: 8) {
                    Image(systemName: downloads > 0 && uploads == 0 ? "arrow.down.circle" : "arrow.up.circle")
                        .accessibilityHidden(true)
                    Text(title)
                        .lineLimit(1)
                    Spacer(minLength: 4)
                    Text(progress.formatted(.percent.precision(.fractionLength(0))))
                        .monospacedDigit()
                        .foregroundStyle(.secondary)
                }
                .font(.subheadline)
                ProgressView(value: progress)
                    .controlSize(.mini)
            }
            .padding(.horizontal, 16)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("transfers.miniBar")
    }

    private var title: String {
        if uploads > 0 && downloads > 0 {
            return String(localized: "传输 \(uploads + downloads) 项", comment: "迷你栏：进行中的上传和下载总数")
        }
        if uploads > 0 {
            return String(localized: "上传 \(uploads) 项", comment: "迷你栏：进行中的上传数")
        }
        return String(localized: "下载 \(downloads) 项", comment: "迷你栏：进行中的下载数")
    }
}
