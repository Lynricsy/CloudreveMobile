import Foundation

/// 后台 `URLSession` 的归属进程。主 App 与分享扩展各用一个标识：同一会话同时只能由一个进程连接，
/// 所以每个进程启动时只连接自己的会话；扩展退出后系统以 `handleEventsForBackgroundURLSession`
/// 把扩展会话的事件交给主 App，主 App 这时才临时连接扩展会话，处理完即释放（见 `BackgroundTransferExecutor`）。
enum TransferSessionRole: Sendable {
    case app
    case shareExtension

    static let appIdentifier = "plus.ling.opencloudreve.transfer"
    static let shareExtensionIdentifier = "plus.ling.opencloudreve.transfer.share"

    /// 本进程连接并发起新请求的会话
    var primaryIdentifier: String {
        switch self {
        case .app: Self.appIdentifier
        case .shareExtension: Self.shareExtensionIdentifier
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
