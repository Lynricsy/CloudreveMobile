import XCTest

/// UI 测试共用的输入与滚动辅助。
/// - 模拟器键盘偶尔丢字（实测“user@example.com”变成“usmple.com”；URL 键盘在字母/数字面板间切换时，
///   “127.0.0.1” 每次都丢同一个 “0”），删除键也会丢，`typeText` 都不报错，所以清空和输入后都必须核对；
/// - 列表是懒加载的：屏幕外的行不在无障碍层级里（`exists` 为 false），被 Tab 栏或底部附件栏遮挡时不可点，需要先滚动。
extension XCUIElement {
    /// 清空输入框并输入 `text`，输入后核对内容（安全输入框按圆点个数核对）。
    /// 首次整串输入；不一致时清空后逐字输入，最多重试 3 次
    @MainActor
    func replaceText(_ text: String, file: StaticString = #filePath, line: UInt = #line) {
        for attempt in 0..<4 {
            guard focusForTyping(), clear() else { continue }
            if attempt == 0 {
                typeText(text)
            } else {
                for character in text { typeText(String(character)) }
            }
            if matches(text) { return }
        }
        XCTFail("输入不完整：期望 \(elementType == .secureTextField ? "\(text.count) 个字符" : text)，实际 \(enteredText)", file: file, line: line)
    }

    /// 是否持有键盘焦点（XCUIElement 的 KVC 属性，Xcode 未公开为 API）
    @MainActor
    var hasKeyboardFocus: Bool {
        (value(forKey: "hasKeyboardFocus") as? Bool) ?? false
    }

    /// 点按获得键盘焦点并核对（App 刚启动时首次点按可能落空，之后 `typeText` 报“没有键盘焦点”）；
    /// 已有内容时再点右端，让光标落在末尾
    @MainActor
    private func focusForTyping() -> Bool {
        var taps = 0
        while !hasKeyboardFocus && taps < 3 {
            tap()
            taps += 1
            let deadline = Date.now.addingTimeInterval(3)
            while !hasKeyboardFocus && Date.now < deadline {
                Thread.sleep(forTimeInterval: 0.3)
            }
        }
        guard hasKeyboardFocus else { return false }
        if !enteredText.isEmpty {
            coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap()
        }
        return true
    }

    /// 逐字删除已有内容并核对已清空（删除键也会丢）
    @MainActor
    private func clear() -> Bool {
        for _ in 0..<3 {
            let current = enteredText
            if current.isEmpty { return true }
            typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: current.count + 2))
        }
        return enteredText.isEmpty
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
