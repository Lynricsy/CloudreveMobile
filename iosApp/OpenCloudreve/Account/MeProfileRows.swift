import PhotosUI
import SwiftUI
import OpenCloudreveCore

/// 账户卡下方的资料行：用户组、更换头像（照片 / Gravatar）、修改昵称
struct MeProfileRows: View {
    let settings: SettingsHost
    @State private var photo: PhotosPickerItem?
    @State private var showingPhotoPicker = false
    @State private var editingNickname = false
    @State private var nicknameDraft = ""

    var body: some View {
        LabeledContent {
            Text(verbatim: settings.state.groupName)
        } label: {
            Label("用户组", systemImage: "person.3")
        }
        .accessibilityIdentifier("me.group")

        Menu {
            Button("从照片中选择", systemImage: "photo.on.rectangle") { showingPhotoPicker = true }
            Button("使用 Gravatar", systemImage: "globe") {
                settings.send(SettingsIntentUseGravatar.shared)
            }
        } label: {
            HStack {
                Label("更换头像", systemImage: "person.crop.circle")
                Spacer()
                if settings.state.saving == .avatar {
                    ProgressView()
                }
            }
        }
        .disabled(settings.state.saving != nil)
        .accessibilityIdentifier("me.avatar")
        .photosPicker(isPresented: $showingPhotoPicker, selection: $photo, matching: .images)
        .task(id: photo) {
            guard let photo else { return }
            await upload(photo)
            self.photo = nil
        }

        Button {
            nicknameDraft = settings.state.nickname
            editingNickname = true
        } label: {
            HStack {
                Label("修改昵称", systemImage: "pencil")
                Spacer()
                if settings.state.saving == .nickname {
                    ProgressView()
                }
            }
        }
        .disabled(settings.state.saving != nil)
        .accessibilityIdentifier("me.nickname")
        .alert("修改昵称", isPresented: $editingNickname) {
            TextField("昵称", text: $nicknameDraft)
            Button("取消", role: .cancel) {}
            Button("保存") {
                settings.send(SettingsIntentSetNickname(nickname: nicknameDraft))
            }
        }
    }

    /// 读取所选照片，居中裁成正方形并压缩为 JPEG 后上传
    private func upload(_ item: PhotosPickerItem) async {
        guard let data = try? await item.loadTransferable(type: Data.self),
              let jpeg = AvatarImage.squareJPEG(from: data) else { return }
        settings.send(SettingsIntentUploadAvatar(bytes: AvatarImage.kotlinBytes(jpeg), contentType: "image/jpeg"))
    }
}
