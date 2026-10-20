import SwiftUI
import OpenCloudreveCore

/// “我的” Tab 根页
struct MeScreen: View {
    var body: some View {
        NavigationStack {
            ViewModelScope({ CoreGraph.shared.accountSwitcherViewModel() }) { host in
                MeContent(host: host)
            }
            .navigationTitle("我的")
        }
    }
}
