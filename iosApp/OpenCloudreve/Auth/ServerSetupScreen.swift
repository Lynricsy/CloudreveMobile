import SwiftUI
import OpenCloudreveCore

/// 服务器地址页：探测 `/site/ping` + `config/basic` 成功后回调规范化后的站点地址
struct ServerSetupScreen: View {
    let onProceed: (String) -> Void

    var body: some View {
        ViewModelScope({ CoreGraph.shared.serverSetupViewModel() }) { host in
            ServerSetupForm(host: host, onProceed: onProceed)
        }
    }
}
