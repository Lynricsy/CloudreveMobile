import XCTest

/// 端到端：文件浏览器。依赖 CI 预置数据（`scripts/ci/seed-files.py`）：
/// 根目录有「照片」（6 张 PNG）、「文档」（65 个空文件）、「工作」「归档」与 `待重命名.txt`、`待删除.txt` 等。
/// 覆盖：根页、列表/网格切换、缩略图、长列表、多选底栏、上下文菜单、重命名、删除到回收站、回收站、搜索。
/// 结束时退出登录，恢复其它 UI 测试期望的未登录状态。
final class FileBrowserUITests: XCTestCase {
    private let serverURL = "http://127.0.0.1:5212"
    private let email = "user@example.com"
    private let password = "User@12345"

    override func setUp() {
        continueAfterFailure = false
    }

    @MainActor
    func testBrowseAndManageFiles() {
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        ensureSignedIn(app)

        // 根页：容量 + 位置
        XCTAssertTrue(element(app, "files.location.my").waitForExistence(timeout: 20), "文件 Tab 根页未显示位置")
        capture(app, "files-01-root")

        // 我的文件：切换到列表
        element(app, "files.location.my").tap()
        XCTAssertTrue(element(app, "file.照片").waitForExistence(timeout: 30), "我的文件未列出预置目录")
        chooseViewMode(app, "列表")
        XCTAssertTrue(element(app, "file.待重命名.txt").waitForExistence(timeout: 10))
        capture(app, "files-02-list")

        // 照片：网格 + 缩略图
        element(app, "file.照片").tap()
        XCTAssertTrue(element(app, "file.photo-01.png").waitForExistence(timeout: 30), "照片目录未列出图片")
        chooseViewMode(app, "网格")
        Thread.sleep(forTimeInterval: 4)
        capture(app, "files-03-grid-thumbnails")
        goBack(app)

        // 文档：65 项长列表 + 多选
        XCTAssertTrue(element(app, "file.文档").waitForExistence(timeout: 10))
        element(app, "file.文档").tap()
        XCTAssertTrue(element(app, "file.note-001.txt").waitForExistence(timeout: 30), "文档目录未列出文件")
        chooseViewMode(app, "列表")
        capture(app, "files-04-long-list")
        openMoreMenu(app)
        let select = app.buttons["files.select"]
        XCTAssertTrue(select.waitForExistence(timeout: 10), "更多菜单中没有“选择”")
        select.tap()
        XCTAssertTrue(app.buttons["files.selectDone"].waitForExistence(timeout: 10), "未进入多选模式")
        element(app, "file.note-001.txt").tap()
        element(app, "file.note-002.txt").tap()
        XCTAssertTrue(app.buttons["files.selection.delete"].waitForExistence(timeout: 10), "多选底栏未出现")
        capture(app, "files-05-multiselect")
        app.buttons["files.selectDone"].tap()
        goBack(app)

        // 上下文菜单 → 重命名
        let renameTarget = element(app, "file.待重命名.txt")
        XCTAssertTrue(renameTarget.waitForExistence(timeout: 10))
        renameTarget.press(forDuration: 1.2)
        let renameItem = app.buttons["重命名"].firstMatch
        XCTAssertTrue(renameItem.waitForExistence(timeout: 10), "上下文菜单未出现")
        capture(app, "files-06-context-menu")
        renameItem.tap()
        let field = app.alerts.firstMatch.textFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 10), "重命名输入框未出现")
        field.tap()
        field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 24))
        field.typeText("已重命名.txt")
        app.alerts.firstMatch.buttons["确定"].firstMatch.tap()
        XCTAssertTrue(element(app, "file.已重命名.txt").waitForExistence(timeout: 20), "重命名后列表未更新")
        capture(app, "files-07-renamed")

        // 删除到回收站
        let deleteTarget = element(app, "file.待删除.txt")
        XCTAssertTrue(deleteTarget.waitForExistence(timeout: 10))
        deleteTarget.press(forDuration: 1.2)
        let deleteItem = app.buttons["删除"].firstMatch
        XCTAssertTrue(deleteItem.waitForExistence(timeout: 10))
        deleteItem.tap()
        let toTrash = app.buttons["移到回收站"].firstMatch
        XCTAssertTrue(toTrash.waitForExistence(timeout: 10), "删除确认未出现")
        toTrash.tap()
        XCTAssertTrue(waitForDisappearance(element(app, "file.待删除.txt"), timeout: 20), "删除后条目仍在列表中")

        // 回收站
        goBack(app)
        XCTAssertTrue(element(app, "files.location.trash").waitForExistence(timeout: 10))
        element(app, "files.location.trash").tap()
        XCTAssertTrue(element(app, "file.待删除.txt").waitForExistence(timeout: 30), "回收站中没有刚删除的文件")
        capture(app, "files-08-trash")
        goBack(app)

        // 搜索
        openSearchTab(app)
        let searchField = app.searchFields.firstMatch
        XCTAssertTrue(searchField.waitForExistence(timeout: 15), "搜索框未出现")
        searchField.tap()
        searchField.typeText("note-00\n")
        XCTAssertTrue(element(app, "file.note-001.txt").waitForExistence(timeout: 30), "搜索没有结果")
        capture(app, "files-09-search")

        signOut(app)
    }

    // MARK: - 步骤

    /// 未登录时走服务器地址 → 登录；已登录则直接进入主界面
    @MainActor
    private func ensureSignedIn(_ app: XCUIApplication) {
        let urlField = app.textFields["server.url"]
        let filesTab = element(app, "tab.files")
        let deadline = Date.now.addingTimeInterval(30)
        while Date.now < deadline && !urlField.exists && !filesTab.exists {
            Thread.sleep(forTimeInterval: 0.5)
        }
        if urlField.exists {
            urlField.tap()
            // 地址框可能已预填（如 https://），先清空
            if let existing = urlField.value as? String, !existing.isEmpty, existing != urlField.placeholderValue {
                urlField.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: existing.count))
            }
            urlField.typeText(serverURL)
            app.buttons["server.continue"].tap()
            let emailField = app.textFields["login.email"]
            XCTAssertTrue(emailField.waitForExistence(timeout: 30), "登录页未出现")
            emailField.tap()
            emailField.typeText(email)
            app.buttons["login.continue"].tap()
            let passwordField = app.secureTextFields["login.password"]
            XCTAssertTrue(passwordField.waitForExistence(timeout: 30), "密码阶段未出现")
            passwordField.tap()
            passwordField.typeText(password)
            app.buttons["login.submit"].tap()
        }
        XCTAssertTrue(filesTab.waitForExistence(timeout: 30), "未进入主界面")
    }

    /// 结束时退出登录（其它 UI 测试从服务器地址页开始）
    @MainActor
    private func signOut(_ app: XCUIApplication) {
        openMeTab(app)
        let signOutButton = app.buttons["me.signOut"]
        var attempts = 0
        while !(signOutButton.exists && signOutButton.isHittable) && attempts < 6 {
            app.swipeUp()
            attempts += 1
        }
        XCTAssertTrue(signOutButton.waitForExistence(timeout: 10), "找不到退出登录按钮")
        signOutButton.tap()
        let confirm = app.sheets.buttons["退出登录"]
        if confirm.waitForExistence(timeout: 5) {
            confirm.tap()
        } else {
            app.buttons.matching(NSPredicate(format: "label == %@ AND identifier != %@", "退出登录", "me.signOut")).firstMatch.tap()
        }
        XCTAssertTrue(app.textFields["server.url"].waitForExistence(timeout: 30), "退出登录后未回到服务器地址页")
    }

    /// 切到“我的”Tab。搜索 Tab 激活时 Tab 栏会收起成搜索框，需要先取消搜索或点收起后的 Tab 按钮
    @MainActor
    private func openMeTab(_ app: XCUIApplication) {
        for _ in 0..<4 {
            let candidates = [app.tabBars.buttons["我的"], app.buttons["我的"].firstMatch]
            if let tab = candidates.first(where: { $0.waitForExistence(timeout: 3) && $0.isHittable }) {
                tab.tap()
                return
            }
            let cancel = app.buttons["取消"].firstMatch
            if cancel.exists && cancel.isHittable {
                cancel.tap()
            } else if app.tabBars.buttons.count > 0 {
                app.tabBars.buttons.element(boundBy: 0).tap()
            }
        }
        XCTFail("找不到“我的” Tab")
    }

    /// 通过“更多”菜单切换列表/网格
    @MainActor
    private func chooseViewMode(_ app: XCUIApplication, _ title: String) {
        openMoreMenu(app)
        let option = app.buttons[title].firstMatch
        XCTAssertTrue(option.waitForExistence(timeout: 10), "更多菜单中没有“\(title)”")
        option.tap()
        Thread.sleep(forTimeInterval: 1)
    }

    @MainActor
    private func openMoreMenu(_ app: XCUIApplication) {
        let more = app.buttons["files.more"].firstMatch
        XCTAssertTrue(more.waitForExistence(timeout: 10), "找不到“更多”菜单")
        more.tap()
    }

    @MainActor
    private func openSearchTab(_ app: XCUIApplication) {
        let candidates = [app.tabBars.buttons["搜索"], app.buttons["搜索"].firstMatch, app.tabBars.buttons["Search"]]
        for candidate in candidates where candidate.exists {
            candidate.tap()
            return
        }
        let tabs = app.tabBars.buttons
        XCTAssertGreaterThan(tabs.count, 0, "找不到 Tab 栏")
        tabs.element(boundBy: tabs.count - 1).tap()
    }

    /// 导航栏返回上一页
    @MainActor
    private func goBack(_ app: XCUIApplication) {
        let back = app.navigationBars.buttons.element(boundBy: 0)
        XCTAssertTrue(back.waitForExistence(timeout: 10), "找不到返回按钮")
        back.tap()
        Thread.sleep(forTimeInterval: 0.8)
    }

    @MainActor
    private func waitForDisappearance(_ element: XCUIElement, timeout: TimeInterval) -> Bool {
        let deadline = Date.now.addingTimeInterval(timeout)
        while element.exists && Date.now < deadline {
            Thread.sleep(forTimeInterval: 0.5)
        }
        return !element.exists
    }

    @MainActor
    private func element(_ app: XCUIApplication, _ identifier: String) -> XCUIElement {
        app.descendants(matching: .any)[identifier].firstMatch
    }

    /// 等动画结束后截全屏并永久保留为测试附件
    @MainActor
    private func capture(_ app: XCUIApplication, _ name: String) {
        Thread.sleep(forTimeInterval: 1)
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
