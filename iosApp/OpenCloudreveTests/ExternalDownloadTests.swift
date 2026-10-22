import Foundation
import Synchronization
import Testing
@testable import OpenCloudreve

/// 外部打开的下载：关闭预览（任务取消）时网络请求被取消，不把文件放进缓存
@Suite(.serialized)
struct ExternalDownloadTests {
    /// 1 秒后才返回内容的 URL 协议；记录请求是否被取消（`stopLoading` 早于响应）。
    /// @unchecked：可变状态只有 `finished`（Mutex），延迟回调需要在其他队列引用自身
    final class SlowURLProtocol: URLProtocol, @unchecked Sendable {
        static let stoppedEarly = Mutex(false)
        private let finished = Mutex(false)

        override class func canInit(with request: URLRequest) -> Bool { true }
        override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

        override func startLoading() {
            DispatchQueue.global().asyncAfter(deadline: .now() + 1) { [self] in
                guard finished.withLock({ done in
                    defer { done = true }
                    return !done
                }) else { return }
                let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
                client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
                client?.urlProtocol(self, didLoad: Data("payload".utf8))
                client?.urlProtocolDidFinishLoading(self)
            }
        }

        override func stopLoading() {
            let early = finished.withLock { done in
                defer { done = true }
                return !done
            }
            if early { Self.stoppedEarly.withLock { $0 = true } }
        }
    }

    private func makeSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [SlowURLProtocol.self]
        return URLSession(configuration: configuration)
    }

    private func destination() -> URL {
        FileManager.default.temporaryDirectory.appending(path: "external-\(UUID().uuidString).bin")
    }

    @Test(.timeLimit(.minutes(1)))
    func closingPreviewCancelsDownloadAndLeavesNoFile() async throws {
        SlowURLProtocol.stoppedEarly.withLock { $0 = false }
        let session = makeSession()
        defer { session.invalidateAndCancel() }
        let target = destination()
        let download = Task {
            try await ExternalDownload.download(from: URL(string: "https://cr.example/f/big.bin")!, to: target, session: session) { _ in }
        }
        try await Task.sleep(for: .milliseconds(200))
        download.cancel()
        let result = await download.result
        #expect(throws: (any Error).self) { try result.get() }
        #expect(SlowURLProtocol.stoppedEarly.withLock { $0 }, "取消后网络请求仍在继续")
        // 等过原本的完成时间：文件不会在之后被移进缓存
        try await Task.sleep(for: .milliseconds(1500))
        #expect(!FileManager.default.fileExists(atPath: target.path(percentEncoded: false)))
    }

    @Test(.timeLimit(.minutes(1)))
    func completedDownloadIsMovedToDestination() async throws {
        let session = makeSession()
        defer { session.invalidateAndCancel() }
        let target = destination()
        defer { try? FileManager.default.removeItem(at: target) }
        try await ExternalDownload.download(from: URL(string: "https://cr.example/f/small.bin")!, to: target, session: session) { _ in }
        #expect(try Data(contentsOf: target) == Data("payload".utf8))
    }
}
