import PhotosUI
import SwiftUI
import OpenCloudreveCore

/// 上传来源
enum UploadMode {
    /// 系统文件选择器（多选），保存安全作用域书签
    case files
    /// 照片与视频（PhotosPicker），复制到收件箱
    case media
    /// 相机拍照，写入收件箱
    case camera
}

/// 上传 sheet：打开即弹出对应的选择器，选好后可改目标目录、查看预校验结果，“开始上传”后加入传输队列
struct UploadSheet: View {
    let mode: UploadMode
    let accountId: String
    let targetUri: String
    let policy: StoragePolicy?
    let onDone: @MainActor () -> Void

    var body: some View {
        NavigationStack {
            ViewModelScope({
                CoreGraph.shared.uploadPickerViewModel(accountId: accountId, targetUri: targetUri, policy: policy)
            }) { host in
                UploadSheetContent(host: host, mode: mode, accountId: accountId, onDone: onDone)
            }
        }
    }
}

struct UploadSheetContent: View {
    let host: UploadPickerHost
    let mode: UploadMode
    let accountId: String
    let onDone: @MainActor () -> Void

    @Environment(\.dismiss) private var dismiss
    /// 候选文件与收件箱副本的所有者：表单以任何方式关闭都会清理未移交的副本
    @State private var draft = UploadDraft()
    @State private var importing = false
    @State private var pickingMedia = false
    @State private var mediaSelection: [PhotosPickerItem] = []
    @State private var capturing = false
    @State private var errorMessage: String?
    @State private var rejected: [RejectedUpload] = []
    @State private var presentedPicker = false

    var body: some View {
        let state = host.state
        Form {
            UploadFormSections(host: host, accountId: accountId, candidates: $draft.candidates)
            Section {
                addButton
                if draft.loadingMedia {
                    HStack {
                        ProgressView()
                        Text("正在读取所选项目…")
                            .foregroundStyle(.secondary)
                    }
                }
                if let errorMessage {
                    ErrorRow(message: errorMessage)
                }
            }
            Section {
                PrimaryActionButton(title: "开始上传", busy: state.enqueuing) {
                    host.send(UploadPickerIntentEnqueue(
                        sources: draft.handOff().map { $0.uploadSource() },
                        targetUri: nil,
                        overwrite: false
                    ))
                }
                .disabled(draft.candidates.isEmpty || state.enqueuing || draft.loadingMedia)
                .accessibilityIdentifier("upload.start")
            }
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        // 入队进行中不能下拉关闭：副本的所有权正在移交
        .interactiveDismissDisabled(state.enqueuing)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("取消", role: .cancel) {
                    draft.close()
                    dismiss()
                }
                .disabled(state.enqueuing)
                .accessibilityIdentifier("upload.cancel")
            }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
            switch result {
            case let .success(urls):
                var failed = 0
                for url in urls {
                    if let candidate = try? UploadCandidate.pickedFile(url) {
                        draft.add(candidate)
                    } else {
                        failed += 1
                    }
                }
                errorMessage = failed > 0 ? String(localized: "有 \(failed) 个文件无法读取", comment: "选择文件时读取失败的数量") : nil
            case let .failure(error):
                errorMessage = error.localizedDescription
            }
        }
        .photosPicker(
            isPresented: $pickingMedia,
            selection: $mediaSelection,
            matching: .any(of: [.images, .videos]),
            preferredItemEncoding: .current
        )
        .onChange(of: mediaSelection) { _, items in
            guard !items.isEmpty else { return }
            mediaSelection = []
            draft.importMedia(count: items.count) { index in
                try await items[index].loadTransferable(type: InboxMediaFile.self)?.url
            } onFinish: { failed in
                errorMessage = failed > 0 ? String(localized: "有 \(failed) 个项目无法读取", comment: "导入照片时失败的数量") : nil
            }
        }
        .fullScreenCover(isPresented: $capturing) {
            CameraCapture { url in
                capturing = false
                guard let url else { return }
                if let candidate = try? UploadCandidate.localFile(url) {
                    draft.add(candidate)
                } else {
                    TransferInbox.discard(sourceRef: url.path(percentEncoded: false))
                }
            }
            .ignoresSafeArea()
        }
        .onAppear {
            guard !presentedPicker else { return }
            presentedPicker = true
            presentPicker()
        }
        .alert("部分文件未加入队列", isPresented: rejectedBinding) {
            Button("好", role: .cancel) { finish() }
        } message: {
            Text(verbatim: rejected.map { "\($0.name)：\(UploadRejectionText.message($0.reason))" }.joined(separator: "\n"))
        }
        .task {
            await host.effects { effect in
                switch onEnum(of: effect) {
                case let .enqueued(enqueued):
                    if enqueued.result.rejected.isEmpty {
                        finish()
                    } else {
                        rejected = enqueued.result.rejected
                    }
                case let .failed(failure):
                    draft.handOffFailed()
                    errorMessage = ErrorText.message(failure.error)
                }
            }
        }
    }

    @ViewBuilder
    private var addButton: some View {
        switch mode {
        case .files:
            Button("选择文件", systemImage: "doc.badge.plus") { importing = true }
        case .media:
            Button("选择照片或视频", systemImage: "photo.badge.plus") { pickingMedia = true }
        case .camera:
            if CameraCapture.isAvailable {
                Button("拍照", systemImage: "camera") { capturing = true }
            } else {
                Label("此设备没有可用的相机", systemImage: "camera.badge.ellipsis")
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var title: LocalizedStringKey {
        switch mode {
        case .files: "上传文件"
        case .media: "上传照片或视频"
        case .camera: "拍照上传"
        }
    }

    private var rejectedBinding: Binding<Bool> {
        Binding(get: { !rejected.isEmpty }, set: { if !$0 { rejected = [] } })
    }

    private func presentPicker() {
        switch mode {
        case .files: importing = true
        case .media: pickingMedia = true
        case .camera: capturing = CameraCapture.isAvailable
        }
    }

    private func finish() {
        onDone()
        dismiss()
    }
}
