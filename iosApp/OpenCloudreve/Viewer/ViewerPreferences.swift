import Foundation

/// “始终使用此方式打开”：按扩展名记住的预览器 id（应用级，保存在 UserDefaults）
enum ViewerPreferences {
    private static func key(_ ext: String) -> String { "viewer.preferred." + ext.lowercased() }

    static func preferred(for ext: String) -> String? {
        UserDefaults.standard.string(forKey: key(ext))
    }

    static func remember(_ viewerId: String, for ext: String) {
        UserDefaults.standard.set(viewerId, forKey: key(ext))
    }
}
