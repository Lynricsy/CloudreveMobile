import SwiftUI
import OpenCloudreveCore

/// 分享一行：类型图标、名称（私密分享带锁）、访问/下载次数、到期时间（已过期标红）、剩余下载次数
struct ShareRow: View {
    let share: Share

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: share.isFolder ? "folder.fill" : "doc.fill")
                .font(.title2)
                .foregroundStyle(share.isFolder ? Color.accentColor : Color.secondary)
                .frame(width: 32)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(verbatim: ShareFormat.title(share))
                        .lineLimit(1)
                        .truncationMode(.middle)
                    if share.isPrivate || share.passwordProtected {
                        Image(systemName: "lock.fill")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .accessibilityLabel(Text("私密分享"))
                    }
                }
                Text("浏览 \(Int(share.visited)) · 下载 \(Int(share.downloaded))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                HStack(spacing: 8) {
                    expiry
                    if let remain = share.remainDownloads {
                        Text("剩余 \(Int(remain.int32Value)) 次下载", comment: "分享剩余可下载次数")
                            .foregroundStyle(.secondary)
                    }
                }
                .font(.caption)
            }
            Spacer(minLength: 0)
        }
        .contentShape(.rect)
    }

    @ViewBuilder
    private var expiry: some View {
        if ShareFormat.isExpired(share) {
            Label("已过期", systemImage: "clock.badge.exclamationmark")
                .foregroundStyle(.red)
        } else if let date = FileFormat.dateTime(share.expires) {
            Label {
                Text("\(date) 到期", comment: "分享到期时间，参数为日期时间")
            } icon: {
                Image(systemName: "clock")
            }
            .foregroundStyle(.secondary)
        } else {
            Label("永久", systemImage: "infinity")
                .foregroundStyle(.secondary)
        }
    }
}
