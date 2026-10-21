import CoreImage.CIFilterBuiltins
import SwiftUI
import UIKit

/// 分享链接二维码（CoreImage `CIQRCodeGenerator`）。按模块原始像素生成，`interpolation(.none)` 放大保持清晰；
/// 尺寸由调用方 `frame` 决定，无障碍标识也由调用方添加。
struct ShareQRCodeImage: View {
    let text: String

    var body: some View {
        if let image = Self.render(text) {
            Image(uiImage: image)
                .interpolation(.none)
                .resizable()
                .scaledToFit()
                .padding(12)
                .background(.white, in: .rect(cornerRadius: 12))
                .accessibilityLabel(Text("二维码"))
        } else {
            Label("无法生成二维码", systemImage: "qrcode")
                .foregroundStyle(.secondary)
        }
    }

    private static let context = CIContext()

    private static func render(_ text: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage,
              let cgImage = context.createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}
