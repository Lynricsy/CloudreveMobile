import UIKit
import OpenCloudreveCore

/// 只用于接收后台 `URLSession` 事件：系统在后台传输结束后唤醒（或启动）App 时调用
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        handleEventsForBackgroundURLSession identifier: String,
        completionHandler: @escaping () -> Void
    ) {
        CoreBootstrap.start()
        let executor = BackgroundTransferExecutor.install(role: .app)
        nonisolated(unsafe) let completion = completionHandler
        executor.handleEvents(forSessionIdentifier: identifier) { completion() }
    }
}
