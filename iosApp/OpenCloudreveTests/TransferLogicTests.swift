import Foundation
import Testing
@testable import OpenCloudreve

/// 传输执行器的纯逻辑：任务描述编码/解析、本地命名、下载路径映射、收件箱判断
struct TransferLogicTests {
    @Test func descriptorRoundTrips() {
        let upload = TransferTaskDescriptor.upload(taskId: "8F0C-1", index: 12)
        #expect(upload.encoded == "8F0C-1#12")
        #expect(TransferTaskDescriptor(parsing: upload.encoded) == upload)

        let download = TransferTaskDescriptor.download(taskId: "8F0C-1")
        #expect(download.encoded == "8F0C-1#download")
        #expect(TransferTaskDescriptor(parsing: download.encoded) == download)
        #expect(download.taskId == "8F0C-1")
    }

    @Test func descriptorSplitsAtLastSeparator() {
        #expect(TransferTaskDescriptor(parsing: "a#b#3") == .upload(taskId: "a#b", index: 3))
        #expect(TransferTaskDescriptor(parsing: "a#b#download") == .download(taskId: "a#b"))
    }

    @Test(arguments: [nil, "", "#3", "abc", "abc#", "abc#-1", "abc#+1", "abc#1.5", "abc#x", "abc#٣", "abc#Download"])
    func descriptorRejectsForeignDescriptions(_ text: String?) {
        #expect(TransferTaskDescriptor(parsing: text) == nil)
    }

    @Test func candidateNamesAppendCounter() {
        #expect(FileNaming.candidate("photo.jpg", index: 0) == "photo.jpg")
        #expect(FileNaming.candidate("photo.jpg", index: 2) == "photo (2).jpg")
        #expect(FileNaming.candidate("README", index: 1) == "README (1)")
        #expect(FileNaming.candidate(".env", index: 1) == ".env (1)")
        #expect(FileNaming.candidate("archive.tar.gz", index: 1) == "archive.tar (1).gz")
    }

    @Test func sanitizeReplacesSeparators() {
        #expect(FileNaming.sanitize("a/b:c", fallback: "x") == "a_b_c")
        #expect(FileNaming.sanitize("  name.txt \n", fallback: "x") == "name.txt")
        #expect(FileNaming.sanitize("..", fallback: "x") == "x")
        #expect(FileNaming.sanitize("", fallback: "") == "file")
    }

    @Test func uniqueURLSkipsExistingFiles() throws {
        let directory = FileManager.default.temporaryDirectory.appending(path: "naming-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        FileManager.default.createFile(atPath: directory.appending(path: "a.txt").path(percentEncoded: false), contents: nil)
        FileManager.default.createFile(atPath: directory.appending(path: "a (1).txt").path(percentEncoded: false), contents: nil)
        #expect(FileNaming.uniqueURL(in: directory, name: "a.txt").lastPathComponent == "a (2).txt")
        #expect(FileNaming.uniqueURL(in: directory, name: "b.txt").lastPathComponent == "b.txt")
    }

    @Test func downloadPathIsRebasedOntoCurrentDocuments() {
        let documents = URL(filePath: "/var/mobile/Containers/Data/Application/NEW/Documents/", directoryHint: .isDirectory)
        let stored = "/var/mobile/Containers/Data/Application/OLD/Documents/Downloads/小明/a b.pdf"
        #expect(
            DownloadDestination.resolve(stored, documents: documents).path(percentEncoded: false)
                == "/var/mobile/Containers/Data/Application/NEW/Documents/Downloads/小明/a b.pdf"
        )
        #expect(DownloadDestination.resolve("/tmp/x.bin", documents: documents).path(percentEncoded: false) == "/tmp/x.bin")
    }

    @Test func downloadDirectoryUsesSanitizedAccountLabel() {
        let documents = URL(filePath: "/D/Documents/", directoryHint: .isDirectory)
        let directory = DownloadDestination.directory(accountLabel: "a/b", documents: documents)
        #expect(directory.standardizedFileURL.path(percentEncoded: false).hasPrefix("/D/Documents/Downloads/a_b"))
    }

    @Test func reserveCreatesPlaceholderWithUniqueName() throws {
        let documents = FileManager.default.temporaryDirectory.appending(path: "docs-\(UUID().uuidString)", directoryHint: .isDirectory)
        defer { try? FileManager.default.removeItem(at: documents) }
        let first = try DownloadDestination.reserve(fileName: "r.txt", accountLabel: "me", documents: documents)
        let second = try DownloadDestination.reserve(fileName: "r.txt", accountLabel: "me", documents: documents)
        #expect(first.lastPathComponent == "r.txt")
        #expect(second.lastPathComponent == "r (1).txt")
        #expect(FileManager.default.fileExists(atPath: second.path(percentEncoded: false)))
    }

    @Test func inboxRecognizesOnlyItsOwnFiles() throws {
        let directory = try TransferInbox.makeBatchDirectory()
        let file = try TransferInbox.write(Data("hi".utf8), into: directory, name: "note.txt")
        let path = file.path(percentEncoded: false)
        #expect(TransferInbox.contains(sourceRef: path))
        #expect(TransferInbox.contains(sourceRef: file.absoluteString))
        #expect(!TransferInbox.contains(sourceRef: "/tmp/other.txt"))
        #expect(!TransferInbox.contains(sourceRef: "Ym9va21hcms="))
        TransferInbox.discard(sourceRef: path)
        #expect(!FileManager.default.fileExists(atPath: path))
        #expect(!FileManager.default.fileExists(atPath: directory.path(percentEncoded: false)))
    }
}
