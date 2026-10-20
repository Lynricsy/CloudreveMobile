import SwiftUI
import OpenCloudreveCore

/// 版本保留：覆盖文件时保留旧版本（可限定扩展名与最多保留数量）
struct VersionRetentionScreen: View {
    let settings: SettingsHost
    @Environment(\.dismiss) private var dismiss
    @State private var enabled = false
    @State private var extensions = ""
    @State private var maxVersions = 0
    @State private var loaded = false
    @State private var failure: String?

    var body: some View {
        Form {
            Section {
                Toggle("启用版本保留", isOn: $enabled)
            } footer: {
                Text("覆盖上传同名文件时保留旧版本，可在文件信息中查看和恢复。")
            }

            if enabled {
                Section {
                    TextField("例如 docx, xlsx, md", text: $extensions)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } header: {
                    Text("文件扩展名")
                } footer: {
                    Text("用逗号分隔；留空表示所有文件。")
                }

                Section {
                    Stepper(value: $maxVersions, in: 0...1000) {
                        LabeledContent("最多保留") {
                            if maxVersions == 0 {
                                Text("不限")
                            } else {
                                Text("\(maxVersions) 个版本")
                            }
                        }
                    }
                }
            }

            Section {
                PrimaryActionButton(title: "保存", busy: settings.state.saving == .versionRetention) {
                    let list = extensions.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }
                    settings.send(SettingsIntentSetVersionRetention(enabled: enabled, extensions: list, max: Int32(maxVersions)))
                }
                .disabled(settings.state.saving != nil)
            }
        }
        .navigationTitle("版本保留")
        .onAppear {
            guard !loaded else { return }
            loaded = true
            enabled = settings.state.versionRetentionEnabled
            extensions = settings.state.versionRetentionExt.joined(separator: ", ")
            maxVersions = Int(settings.state.versionRetentionMax)
        }
        .task {
            await settings.effects { effect in
                switch onEnum(of: effect) {
                case let .saved(saved):
                    if saved.field == .versionRetention { dismiss() }
                case let .failed(failed):
                    failure = ErrorText.message(failed.error)
                }
            }
        }
        .errorMessageAlert("保存失败", message: $failure)
    }
}
