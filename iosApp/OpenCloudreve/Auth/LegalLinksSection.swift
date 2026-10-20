import SwiftUI

/// 服务条款与隐私政策链接（站点未配置时不显示）
struct LegalLinksSection: View {
    let tosUrl: String?
    let privacyPolicyUrl: String?

    var body: some View {
        let tos = tosUrl.flatMap(URL.init(string:))
        let privacy = privacyPolicyUrl.flatMap(URL.init(string:))
        if tos != nil || privacy != nil {
            Section {
                if let tos {
                    Link("服务条款", destination: tos)
                }
                if let privacy {
                    Link("隐私政策", destination: privacy)
                }
            }
        }
    }
}
