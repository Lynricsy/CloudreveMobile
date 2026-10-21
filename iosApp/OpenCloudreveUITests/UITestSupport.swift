import XCTest

/// UI 测试共用的输入与滚动辅助。
/// - 模拟器键盘在输入框获得焦点、键盘动画或切换符号键盘时偶尔丢字（实测“user@example.com”变成“usmple.com”），
///   `typeText` 不会报错，所以输入后必须核对，不一致时清空重输；
/// - 列表是懒加载的：屏幕外的行不在无障碍层级里（`exists` 为 false），被 Tab 栏或底部附件栏遮挡时不可点，需要先滚动。
extension XCUIElement {
    /// 清空输入框并输入 `text`，输入后核对内容（安全输入框按圆点个数核对），不一致时重输，最多 3 次
    @MainActor
    func replaceText(_ text: String, file: StaticString = #filePath, line: UInt = #line) {
        for _ in 0..<3 {
            // 点在右端让光标落在末尾，再逐字删掉已有内容（可能是预填的 “https://” 或上次丢字后的残留）
            coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap()
            let current = enteredText
            if !current.isEmpty {
                typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: current.count + 2))
            }
            typeText(text)
            if matches(text) { return }
        }
        XCTFail("输入不完整：期望 \(elementType == .secureTextField ? "\(text.count) 个字符" : text)，实际 \(enteredText)", file: file, line: line)
    }

    /// 输入框中的文字；空输入框的 `value` 是占位符，按空处理
    @MainActor
    var enteredText: String {
        guard let current = value as? String, current != placeholderValue else { return "" }
        return current
    }

    @MainActor
    private func matches(_ text: String) -> Bool {
        let current = enteredText
        if elementType == .secureTextField {
            return current.count == text.count && current.allSatisfy { $0 == "•" }
        }
        return current == text
    }
}

extension XCUIApplication {
    /// 目标不存在（懒加载列表尚未渲染）或不可点（被 Tab 栏 / 底部附件栏遮挡）时上滑，最多 `maxSwipes` 次；返回最终是否可点
    @MainActor
    @discardableResult
    func scrollUntilHittable(_ target: XCUIElement, maxSwipes: Int = 6) -> Bool {
        var swipes = 0
        while !(target.exists && target.isHittable) && swipes < maxSwipes {
            swipeUp()
            swipes += 1
        }
        return target.exists && target.isHittable
    }
}
