import Foundation
import Synchronization

/// 测试用 URL 协议：记录经过的请求地址，并以网络错误结束请求（只关心“是否发出”）
final class RecordingURLProtocol: URLProtocol {
    private static let recorded = Mutex<[URL]>([])

    static func reset() {
        recorded.withLock { $0.removeAll() }
    }

    static func requests(containing fragment: String) -> [URL] {
        recorded.withLock { $0.filter { $0.absoluteString.contains(fragment) } }
    }

    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        if let url = request.url {
            Self.recorded.withLock { $0.append(url) }
        }
        client?.urlProtocol(self, didFailWithError: URLError(.notConnectedToInternet))
    }

    override func stopLoading() {}
}
