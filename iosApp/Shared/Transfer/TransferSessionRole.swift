import Foundation

/// 后台 `URLSession` 的归属进程。主 App 与分享扩展各用一个标识（同一标识不能被两个进程同时连接）；
/// 主 App 还会接管分享扩展留下的会话（扩展退出后其后台任务的事件交给主 App 处理）。
enum TransferSessionRole: Sendable {
    case app
    case shareExtension

    static let appIdentifier = "plus.ling.opencloudreve.transfer"
    static let shareExtensionIdentifier = "plus.ling.opencloudreve.transfer.share"

    /// 本进程发起新请求所用的会话
    var primaryIdentifier: String {
        switch self {
        case .app: Self.appIdentifier
        case .shareExtension: Self.shareExtensionIdentifier
        }
    }

    /// 本进程需要连接的全部会话（第一个为主会话）
    var sessionIdentifiers: [String] {
        switch self {
        case .app: [Self.appIdentifier, Self.shareExtensionIdentifier]
        case .shareExtension: [Self.shareExtensionIdentifier]
        }
    }

    /// 后台会话配置：共享 App Group 容器（扩展的下载/上传文件对主 App 可见），完成后唤醒主 App
    static func backgroundConfiguration(identifier: String) -> URLSessionConfiguration {
        let configuration = URLSessionConfiguration.background(withIdentifier: identifier)
        configuration.sharedContainerIdentifier = AppGroup.identifier
        configuration.sessionSendsLaunchEvents = true
        configuration.isDiscretionary = false
        configuration.allowsCellularAccess = true
        return configuration
    }
}
