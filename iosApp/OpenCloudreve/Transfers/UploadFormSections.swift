import SwiftUI
import OpenCloudreveCore

/// 上传表单的公共部分（App 内上传与分享扩展共用）：目标目录（可改）、存储策略限制、待上传文件列表（含预校验提示）
struct UploadFormSections: View {
    let host: UploadPickerHost
    let accountId: String
    @Binding var candidates: [UploadCandidate]

    @State private var pickingFolder = false

    var body: some View {
        let state = host.state
        Section("上传到") {
            Button {
                pickingFolder = true
            } label: {
                HStack {
                    Label {
                        Text(verbatim: displayPath(state.targetUri))
                            .foregroundStyle(.primary)
                    } icon: {
                        Image(systemName: "folder")
                    }
                    Spacer()
                    Image(systemName: "chevron.right")
                        .font(.footnote)
                        .foregroundStyle(.tertiary)
                }
            }
            .accessibilityIdentifier("upload.target")
            .sheet(isPresented: $pickingFolder) {
                FolderPickerSheet(accountId: accountId, initialUri: state.targetUri, title: "选择上传位置") { uri in
                    host.send(UploadPickerIntentSetTarget(uri: uri, policy: nil))
                }
            }
            if state.loadingPolicy {
                HStack {
                    ProgressView()
                    Text("正在读取存储策略…")
                        .foregroundStyle(.secondary)
                }
            }
            if let error = state.policyError {
                ErrorRow(message: ErrorText.message(error))
            }
            if state.maxSize > 0 {
                Label(
                    String(localized: "单个文件不超过 \(FileFormat.bytes(state.maxSize))", comment: "存储策略的单文件大小上限"),
                    systemImage: "info.circle"
                )
                .foregroundStyle(.secondary)
            }
            if !state.allowedSuffix.isEmpty {
                Label(
                    String(localized: "仅允许：\(state.allowedSuffix.joined(separator: ", "))", comment: "存储策略允许的扩展名"),
                    systemImage: "checkmark.circle"
                )
                .foregroundStyle(.secondary)
            }
            if !state.deniedSuffix.isEmpty {
                Label(
                    String(localized: "禁止：\(state.deniedSuffix.joined(separator: ", "))", comment: "存储策略禁止的扩展名"),
                    systemImage: "nosign"
                )
                .foregroundStyle(.secondary)
            }
        }
        if !candidates.isEmpty {
            Section {
                ForEach(candidates) { candidate in
                    candidateRow(candidate)
                }
                .onDelete { offsets in
                    offsets.map { candidates[$0] }.forEach { $0.discard() }
                    candidates.remove(atOffsets: offsets)
                }
            } header: {
                Text("\(candidates.count) 个文件", comment: "待上传文件数")
            }
        }
    }

    private func candidateRow(_ candidate: UploadCandidate) -> some View {
        let rejection = host.check(name: candidate.name, size: candidate.size)
        return VStack(alignment: .leading, spacing: 2) {
            Text(verbatim: candidate.name)
                .lineLimit(1)
                .truncationMode(.middle)
                .accessibilityIdentifier("upload.file." + candidate.name)
            Text(verbatim: FileFormat.bytes(candidate.size))
                .font(.caption)
                .foregroundStyle(.secondary)
            if let rejection {
                Label(UploadRejectionText.message(rejection), systemImage: "exclamationmark.triangle.fill")
                    .font(.caption)
                    .foregroundStyle(.red)
            }
        }
    }

    private func displayPath(_ uri: String) -> String {
        CrUri.companion.parseOrNull(s: uri).map(FileFormat.displayPath) ?? uri
    }
}
