import CoreImage.CIFilterBuiltins
import SwiftUI

/// TOTP 二维码（CoreImage `CIQRCodeGenerator`），按像素放大避免模糊
struct TwoFactorQRCode: View {
    let text: String

    var body: some View {
        if let image = Self.render(text) {
            Image(uiImage: image)
                .interpolation(.none)
                .resizable()
                .scaledToFit()
                .frame(width: 220, height: 220)
                .padding(12)
                .background(.white, in: .rect(cornerRadius: 12))
                .accessibilityLabel(Text("两步验证二维码"))
                .accessibilityIdentifier("twofa.qr")
        } else {
            Label("无法生成二维码", systemImage: "qrcode")
                .foregroundStyle(.secondary)
        }
    }

    private static func render(_ text: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)),
              let cgImage = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}
