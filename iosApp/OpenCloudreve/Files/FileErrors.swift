import Foundation
import OpenCloudreveCore

/// Swift 侧捕获到的错误 → 本地化文案
enum FileErrors {
    /// SKIE 抛出的 NSError 在 userInfo["KotlinException"] 中带原始 Kotlin 异常，按共享层规则映射为 `UiError`
    static func message(_ error: any Error) -> String {
        if let throwable = (error as NSError).userInfo["KotlinException"] as? KotlinThrowable {
            return ErrorText.message(throwable.toUiError())
        }
        return ErrorText.message(UiError(kind: .network, code: nil, serverMsg: nil))
    }
}
