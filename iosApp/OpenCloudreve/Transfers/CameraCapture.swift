import SwiftUI
import UIKit

/// 系统相机（仅拍照）：拍摄后把 JPEG 写入 App Group `Inbox/`，回调文件 URL；取消回调 nil
struct CameraCapture: UIViewControllerRepresentable {
    let onFinish: (URL?) -> Void

    static var isAvailable: Bool {
        UIImagePickerController.isSourceTypeAvailable(.camera)
    }

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.mediaTypes = ["public.image"]
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(onFinish: onFinish)
    }

    @MainActor
    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let onFinish: (URL?) -> Void

        init(onFinish: @escaping (URL?) -> Void) {
            self.onFinish = onFinish
        }

        func imagePickerController(
            _ picker: UIImagePickerController,
            didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
        ) {
            guard let image = info[.originalImage] as? UIImage, let data = image.jpegData(compressionQuality: 0.9) else {
                onFinish(nil)
                return
            }
            let formatter = DateFormatter()
            formatter.locale = Locale(identifier: "en_US_POSIX")
            formatter.dateFormat = "yyyyMMdd_HHmmss"
            let name = "IMG_" + formatter.string(from: .now) + ".jpg"
            let url = try? TransferInbox.write(data, into: TransferInbox.makeBatchDirectory(), name: name)
            onFinish(url)
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            onFinish(nil)
        }
    }
}
