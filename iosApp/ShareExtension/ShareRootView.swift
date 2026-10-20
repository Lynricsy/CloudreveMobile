import SwiftUI
import OpenCloudreveCore

/// 分享扩展根视图：按阶段显示加载、上传表单、分享链接提示或完成页
struct ShareRootView: View {
    let session: ShareSession

    var body: some View {
        NavigationStack {
            content
                .navigationTitle("上传到 OpenCloudreve")
                .navigationBarTitleDisplayMode(.inline)
        }
    }

    @ViewBuilder
    private var content: some View {
        switch session.phase {
        case .loading:
            ProgressView("正在读取分享的内容…")
                .toolbar { cancelItem }
        case let .upload(candidates, skipped):
            let accounts = CoreGraph.shared.accounts().accounts.value.filter { !$0.sessionExpired }
            if accounts.isEmpty {
                message(
                    title: "尚未登录",
                    systemImage: "person.crop.circle.badge.questionmark",
                    detail: "请先打开 OpenCloudreve App 登录账户，再从分享菜单上传。"
                )
                .onAppear { candidates.forEach { $0.discard() } }
            } else {
                ShareUploadView(session: session, accounts: accounts, initialCandidates: candidates, skipped: skipped)
            }
        case let .link(link):
            ShareLinkView(session: session, link: link)
        case .nothing:
            message(
                title: "没有可上传的内容",
                systemImage: "doc.questionmark",
                detail: "只能上传文件、照片和视频，或打开 OpenCloudreve 分享链接。"
            )
        case .enqueuing:
            ProgressView("正在开始上传…")
        case let .done(accepted, rejected):
            ShareDoneView(session: session, accepted: accepted, rejected: rejected)
        }
    }

    private func message(title: LocalizedStringKey, systemImage: String, detail: LocalizedStringKey) -> some View {
        ContentUnavailableView {
            Label(title, systemImage: systemImage)
        } description: {
            Text(detail)
        }
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("好") { session.cancel() }
            }
        }
    }

    private var cancelItem: some ToolbarContent {
        ToolbarItem(placement: .cancellationAction) {
            Button("取消", role: .cancel) { session.cancel() }
        }
    }
}

/// 完成页：已加入队列的数量与被存储策略拒绝的文件
private struct ShareDoneView: View {
    let session: ShareSession
    let accepted: Int
    let rejected: [String]

    var body: some View {
        Form {
            Section {
                Label {
                    Text("已开始上传 \(accepted) 个文件", comment: "分享扩展完成页")
                } icon: {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundStyle(.green)
                }
                Text("上传会在后台继续，可在 App 的“任务”页查看进度。")
                    .foregroundStyle(.secondary)
            }
            if !rejected.isEmpty {
                Section("未上传") {
                    ForEach(rejected, id: \.self) { line in
                        Text(verbatim: line)
                            .foregroundStyle(.red)
                    }
                }
            }
        }
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("完成") { session.complete() }
            }
        }
    }
}
