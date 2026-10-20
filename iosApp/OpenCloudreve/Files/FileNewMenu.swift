import SwiftUI
import OpenCloudreveCore

/// 右上角“新建”菜单：新建文件夹、按预览器模板新建文件，以及后续阶段接入的上传入口
struct FileNewMenu: View {
    let options: [NewFileOption]
    let context: FileActionContext
    let driver: FileActionDriver

    var body: some View {
        Menu {
            Button("新建文件夹", systemImage: "folder.badge.plus") {
                driver.newFolderName = String(localized: "新建文件夹")
                driver.creatingFolder = true
            }
            .accessibilityIdentifier("files.newFolder")
            if !options.isEmpty {
                Menu {
                    ForEach(options, id: \.template.ext) { option in
                        Button {
                            driver.newFileName = String(localized: "新文件") + "." + option.template.ext
                            driver.newFileOption = option
                        } label: {
                            Text(verbatim: displayName(option))
                        }
                    }
                } label: {
                    Label("新建文件", systemImage: "doc.badge.plus")
                }
            }
            uploadSection
        } label: {
            Label("新建", systemImage: "plus")
        }
        .accessibilityIdentifier("files.new")
    }

    @ViewBuilder
    private var uploadSection: some View {
        let features = FileFeatures.installed
        let hookContext = context.hookContext(files: [], folderUri: context.listingUri)
        if features.uploadFiles != nil || features.uploadMedia != nil || features.uploadCamera != nil {
            Section {
                if let hook = features.uploadFiles {
                    Button("上传文件", systemImage: "doc.badge.arrow.up") {
                        driver.present(hook, context: hookContext)
                    }
                }
                if let hook = features.uploadMedia {
                    Button("上传照片或视频", systemImage: "photo.badge.arrow.down") {
                        driver.present(hook, context: hookContext)
                    }
                }
                if let hook = features.uploadCamera {
                    Button("拍照上传", systemImage: "camera") {
                        driver.present(hook, context: hookContext)
                    }
                }
            }
        }
    }

    /// 模板显示名：服务端默认表多为网页端 i18n key（如 `fileManager.text`），已知的翻译，未知的用扩展名
    private func displayName(_ option: NewFileOption) -> String {
        let name = option.template.displayName
        switch name {
        case "fileManager.text": return String(localized: "文本文件")
        case "fileManager.diagram": return String(localized: "Draw.io 图表")
        case "fileManager.whiteboard": return String(localized: "白板")
        default: break
        }
        if name.isEmpty || name.hasPrefix("fileManager.") {
            return "." + option.template.ext
        }
        return name
    }
}
