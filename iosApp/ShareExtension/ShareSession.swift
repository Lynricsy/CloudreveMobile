import Foundation
import Observation

/// 分享扩展的界面阶段
enum SharePhase {
    case loading
    case upload(candidates: [UploadCandidate], skipped: Int)
    case link(String)
    case nothing
    case enqueuing
    case done(accepted: Int, rejected: [String])
}

/// 分享扩展会话：阶段 + 与宿主 `NSExtensionContext` 交互的动作
@MainActor
@Observable
final class ShareSession {
    var phase: SharePhase = .loading

    @ObservationIgnored let complete: @MainActor () -> Void
    @ObservationIgnored let cancel: @MainActor () -> Void
    @ObservationIgnored let open: @MainActor (URL) async -> Bool

    init(
        complete: @escaping @MainActor () -> Void,
        cancel: @escaping @MainActor () -> Void,
        open: @escaping @MainActor (URL) async -> Bool
    ) {
        self.complete = complete
        self.cancel = cancel
        self.open = open
    }

    func apply(_ result: ShareLoadResult) {
        if !result.candidates.isEmpty {
            phase = .upload(candidates: result.candidates, skipped: result.skipped)
        } else if let link = result.shareLink {
            phase = .link(link)
        } else {
            phase = .nothing
        }
    }
}
