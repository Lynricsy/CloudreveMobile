import FileProvider
import OpenCloudreveCore

/// 共享层错误 → `NSFileProviderError`（系统据此提示登录、重试或改名）
enum FileProviderErrorMap {
    /// 按服务端/客户端错误码映射；没有对应系统错误码时返回 nil（原样上报）
    static func code(forErrorCode code: Int32) -> NSFileProviderError.Code? {
        let codes = ErrorCode.shared
        switch code {
        case codes.LOGIN_REQUIRED, codes.CREDENTIAL_INVALID:
            return .notAuthenticated
        case codes.NOT_FOUND, codes.FILE_NOT_FOUND, codes.PARENT_NOT_EXIST, codes.FILE_DELETED:
            return .noSuchItem
        case codes.OBJECT_EXIST:
            return .filenameCollision
        case codes.INSUFFICIENT_CAPACITY:
            return .insufficientQuota
        case codes.TRANSPORT, codes.BAD_RESPONSE:
            return .serverUnreachable
        default:
            return nil
        }
    }

    /// 把 Swift 侧捕获到的错误（SKIE 包装的 Kotlin 异常、取消）转换成交给系统的错误
    static func providerError(_ error: any Error) -> any Error {
        if error is CancellationError { return CocoaError(.userCancelled) }
        let nsError = error as NSError
        if nsError.domain == NSFileProviderErrorDomain { return error }
        if nsError.domain == NSURLErrorDomain { return NSFileProviderError(.serverUnreachable) }
        guard let throwable = nsError.userInfo["KotlinException"] as? KotlinThrowable else { return error }
        let code: NSFileProviderError.Code?
        switch throwable {
        case is SessionExpiredException:
            code = .notAuthenticated
        case let api as ApiException:
            code = Self.code(forErrorCode: api.code)
        case let transfer as TransferFailedException:
            code = Self.code(forErrorCode: transfer.code)
        case is TransportException:
            code = .serverUnreachable
        default:
            code = nil
        }
        return code.map { NSFileProviderError($0) } ?? error
    }
}
