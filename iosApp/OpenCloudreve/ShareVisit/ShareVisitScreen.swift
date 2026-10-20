import SwiftUI
import OpenCloudreveCore

/// 浏览分享链接（`https://<site>/s/<id>[/<pw>]`、`cloudreve://<id>[:<pw>]@share`、纯 id）。
/// 不自带导航栈：可推入任意栈或放进 `ShareVisitSheet`。
struct ShareVisitScreen: View {
    let accountId: String
    let link: String

    var body: some View {
        ViewModelScope({ CoreGraph.shared.shareVisitViewModel(accountId: accountId, link: link) }) { host in
            ShareVisitContent(accountId: accountId, host: host)
        }
    }
}
