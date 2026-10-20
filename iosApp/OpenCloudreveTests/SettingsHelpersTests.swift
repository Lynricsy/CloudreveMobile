import Testing
import OpenCloudreveCore
@testable import OpenCloudreve

/// 设置/管理页面的纯函数：主题色解析与选择、WebDAV 根目录显示、JSON 格式化
@MainActor
struct SettingsHelpersTests {
    @Test func parsesThemeHexColors() {
        #expect(ThemeColor.parse("#1976d2") != nil)
        #expect(ThemeColor.parse("#fff") != nil)
        #expect(ThemeColor.parse("#1976d2ff") != nil)
        #expect(ThemeColor.parse("1976d2") == nil)
        #expect(ThemeColor.parse("#12") == nil)
        #expect(ThemeColor.parse("#zzzzzz") == nil)
    }

    @Test func picksThemeColorsBySelectedKey() {
        let options = [
            ThemeColorOption(key: "#1976d2", lightPrimary: "#1976d2", darkPrimary: "#90caf9"),
            ThemeColorOption(key: "#3f51b5", lightPrimary: "#3f51b5", darkPrimary: "#9fa8da"),
        ]
        let picked = ThemeTintStore.colors(options: options, selected: "#3f51b5")
        #expect(picked.light == "#3f51b5")
        #expect(picked.dark == "#9fa8da")
        // 不在选项内但本身是颜色：直接使用
        #expect(ThemeTintStore.colors(options: options, selected: "#ff0000").light == "#ff0000")
        // 无效或未选：清空，回到默认强调色
        #expect(ThemeTintStore.colors(options: options, selected: "unknown").light == "")
        #expect(ThemeTintStore.colors(options: options, selected: nil).dark == "")
    }

    @Test func parsesSiteThemesJson() {
        let json = ##"{"#1976d2":{"light":{"palette":{"primary":{"main":"#1976d2"}}},"dark":{"palette":{"primary":{"main":"#90caf9"}}}}}"##
        let options = SettingsViewModel.companion.parseThemes(themes: json)
        #expect(options.count == 1)
        #expect(options.first?.darkPrimary == "#90caf9")
    }

    @Test func displaysDavRootUri() {
        let root = String(localized: "我的文件")
        #expect(DavUriText.display("cloudreve://my") == root)
        #expect(DavUriText.display("cloudreve://my/") == root)
        #expect(DavUriText.display("cloudreve://my/Docs/%E7%85%A7%E7%89%87") == root + "/Docs/照片")
        #expect(DavUriText.display("cloudreve://abc@share") == "cloudreve://abc@share")
    }

    @Test func prettyPrintsJson() {
        #expect(AdminJSON.pretty(#"{"b":1,"a":[true]}"#).contains("\"a\" : ["))
        #expect(AdminJSON.pretty("not json") == "not json")
    }
}
