import SwiftUI
import UIKit

/// 图形验证码图片，点按刷新。输入只有 base64 字符串，答案输入时不会重新解码图片。
struct CaptchaImageButton: View {
    let base64: String?
    let loading: Bool
    let refresh: () -> Void

    var body: some View {
        Button(action: refresh) {
            ZStack {
                if let image = decoded {
                    Image(uiImage: image)
                        .resizable()
                        .interpolation(.none)
                        .scaledToFit()
                } else if !loading {
                    Image(systemName: "arrow.clockwise")
                }
                if loading {
                    ProgressView()
                }
            }
            .frame(width: 120, height: 44)
            .background(.quaternary, in: .rect(cornerRadius: 8))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text("刷新验证码"))
        .accessibilityIdentifier("captcha.image")
    }

    private var decoded: UIImage? {
        guard let base64, let data = Data(base64Encoded: base64, options: .ignoreUnknownCharacters) else { return nil }
        return UIImage(data: data)
    }
}
