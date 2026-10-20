import Foundation
import OpenCloudreveCore

/// `sheet(item:)` 用的文件包装（Kotlin 对象本身不是 Identifiable）
struct FileInfoTarget: Identifiable {
    let id = UUID()
    let file: FileItem
}
