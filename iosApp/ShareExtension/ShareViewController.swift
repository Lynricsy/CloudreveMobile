import UIKit
import SwiftUI

/// 系统分享菜单入口“上传到 OpenCloudreve”：附件复制到 App Group 收件箱 → 选账户与目录 → 入队，
/// 由扩展自己的后台会话发出第一批请求后关闭；之后的分片由系统继续，扩展退出后交给主 App 接管。
final class ShareViewController: UIViewController {
    override func viewDidLoad() {
        super.viewDidLoad()
        CoreBootstrap.start()
        BackgroundTransferExecutor.install(role: .shareExtension)

        let session = ShareSession(
            complete: { [weak self] in
                self?.extensionContext?.completeRequest(returningItems: nil)
            },
            cancel: { [weak self] in
                self?.extensionContext?.cancelRequest(withError: CocoaError(.userCancelled))
            },
            open: { [weak self] url in
                guard let context = self?.extensionContext else { return false }
                return await withCheckedContinuation { continuation in
                    context.open(url) { @Sendable opened in continuation.resume(returning: opened) }
                }
            }
        )
        let attachments = (extensionContext?.inputItems as? [NSExtensionItem] ?? [])
            .flatMap { $0.attachments ?? [] }
            .map(SharedAttachment.init(provider:))
        Task {
            session.apply(await ShareAttachmentLoader.load(attachments))
        }

        let host = UIHostingController(rootView: ShareRootView(session: session))
        addChild(host)
        host.view.frame = view.bounds
        host.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(host.view)
        host.didMove(toParent: self)
    }
}
