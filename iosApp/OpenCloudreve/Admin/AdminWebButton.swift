import SwiftUI
import OpenCloudreveCore

/// 导航栏“在网页后台打开”（会话交接）；服务端版本过低时隐藏
struct AdminWebButton: View {
    let availability: HandoffAvailability
    let opening: Bool
    let action: () -> Void

    var body: some View {
        if availability != .unsupported {
            if opening {
                ProgressView()
            } else {
                Button("在网页后台打开", systemImage: "safari", action: action)
                    .accessibilityIdentifier("admin.openWeb")
            }
        }
    }
}
