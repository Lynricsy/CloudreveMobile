import CoreTransferable
import Foundation
import UniformTypeIdentifiers

/// 从照片选择器导入的媒体：直接复制到 App Group `Inbox/<uuid>/`。
/// 先匹配图片表示，Live Photo 因此只得到照片本体（不含配对视频）；普通视频匹配影片表示。
struct InboxMediaFile: Transferable {
    let url: URL

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(importedContentType: .image) { received in try copy(received) }
        FileRepresentation(importedContentType: .movie) { received in try copy(received) }
    }

    private static func copy(_ received: ReceivedTransferredFile) throws -> InboxMediaFile {
        let directory = try TransferInbox.makeBatchDirectory()
        let url = try TransferInbox.copy(received.file, into: directory, name: received.file.lastPathComponent)
        return InboxMediaFile(url: url)
    }
}
