import UIKit
import OpenCloudreveCore

/// 头像处理：居中裁正方形、缩放并压缩为 JPEG
enum AvatarImage {
    /// 头像边长（像素）
    static let side: CGFloat = 256

    @MainActor
    static func squareJPEG(from data: Data) -> Data? {
        guard let image = UIImage(data: data), image.size.width > 0, image.size.height > 0 else { return nil }
        let scale = side / min(image.size.width, image.size.height)
        let drawSize = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        let origin = CGPoint(x: (side - drawSize.width) / 2, y: (side - drawSize.height) / 2)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: side, height: side), format: format)
        let square = renderer.image { _ in
            image.draw(in: CGRect(origin: origin, size: drawSize))
        }
        return square.jpegData(compressionQuality: 0.85)
    }

    /// `Data` → Kotlin `ByteArray`
    static func kotlinBytes(_ data: Data) -> KotlinByteArray {
        let array = KotlinByteArray(size: Int32(data.count))
        for (index, byte) in data.enumerated() {
            array.set(index: Int32(index), value: Int8(bitPattern: byte))
        }
        return array
    }
}
