import Foundation
import Observation
import Photos

/// 实况照片：下载图片与配对的 MOV 后用 `PHLivePhoto.request(withResourceFileURLs:)` 组装
@MainActor
@Observable
final class LivePhotoLoader {
    private(set) var livePhoto: PHLivePhoto?
    private(set) var failed = false

    func load(imageURL: String?, videoURL: String?, fileName: String) async {
        guard let imageURL, let videoURL, let image = URL(string: imageURL), let video = URL(string: videoURL) else { return }
        failed = false
        do {
            let imageFile = try await RemoteFile.download(from: image, fileName: fileName)
            let videoName = (fileName as NSString).deletingPathExtension + ".mov"
            let videoFile = try await RemoteFile.download(from: video, fileName: videoName)
            request(files: [imageFile, videoFile])
        } catch {
            failed = true
        }
    }

    private func request(files: [URL]) {
        PHLivePhoto.request(
            withResourceFileURLs: files,
            placeholderImage: nil,
            targetSize: .zero,
            contentMode: .aspectFit
        ) { [weak self] photo, info in
            let isError = info[PHLivePhotoInfoErrorKey] != nil || (info[PHLivePhotoInfoCancelledKey] as? NSNumber)?.boolValue == true
            // Photos 在主队列回调结果
            MainActor.assumeIsolated {
                guard let self else { return }
                if let photo {
                    self.livePhoto = photo
                } else if isError {
                    self.failed = true
                }
            }
        }
    }
}
