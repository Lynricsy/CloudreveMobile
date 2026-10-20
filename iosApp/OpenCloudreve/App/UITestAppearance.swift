import SwiftUI

/// UI 测试夹具（仅 Debug）：启动参数 `-OCRUITestColorScheme dark|light` 强制界面配色
/// （CI 模拟器上 `XCUIDevice.appearance` 不生效）。未传参数或 Release 构建时跟随系统。
enum UITestAppearance {
    static let argumentKey = "OCRUITestColorScheme"

    static var colorScheme: ColorScheme? {
        #if DEBUG
        switch UserDefaults.standard.string(forKey: argumentKey) {
        case "dark": .dark
        case "light": .light
        default: nil
        }
        #else
        nil
        #endif
    }
}
