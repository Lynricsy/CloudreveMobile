import Testing
import OpenCloudreveCore
@testable import OpenCloudreve

/// 文件浏览器的纯逻辑：动作显示规则（规格 §7.7）、图标表与颜色解析、格式化
@MainActor
struct FileBrowserLogicTests {
    /// 能力位 0–24 全开（`props.capability` 的 base64）
    private let allCapabilities = BoolSet.companion.fromBase64(s: "////AQ==")

    private func file(
        _ name: String,
        path: String,
        folder: Bool = false,
        owned: Bool = true,
        metadata: [String: String] = [:]
    ) -> FileItem {
        FileItem(
            type: folder ? FileType.shared.FOLDER : FileType.shared.FILE,
            id: name,
            name: name,
            createdAt: nil,
            updatedAt: nil,
            size: 1024,
            metadata: metadata,
            path: path,
            shared: false,
            capability: allCapabilities,
            owned: owned,
            primaryEntity: nil,
            sha256: nil,
            hashStatus: nil,
            hashReason: nil,
            folderSummary: nil,
            extendedInfo: nil
        )
    }

    private func user(permissionBase64: String) -> User {
        User(
            id: "u1",
            email: "user@example.com",
            nickname: "user",
            status: "active",
            avatar: nil,
            createdAt: nil,
            preferredTheme: nil,
            anonymous: false,
            group: UserGroup(
                id: "g1",
                name: "User",
                permission: BoolSet.companion.fromBase64(s: permissionBase64),
                directLinkBatchSize: 10,
                trashRetention: 0
            ),
            pined: [],
            language: nil,
            disableViewSync: false,
            shareLinksInProfile: ""
        )
    }

    @Test func ownFileInMyFilesOffersManagementActions() {
        // 组权限：share(2)，无 archive/remote download
        let user = user(permissionBase64: "BA==")
        let target = file("a.txt", path: "cloudreve://my/a.txt")
        let actions = FileActionPolicy.available(targets: [target], user: user)
        #expect(actions.isSuperset(of: [.rename, .move, .copy, .delete, .info, .directLink, .share]))
        #expect(!actions.contains(.pin), "固定只对目录")
        #expect(!actions.contains(.createArchive))
        #expect(!actions.contains(.open), "没有预览器时不提供打开")
    }

    @Test func folderCanBePinnedAndMounted() {
        let user = user(permissionBase64: "AA==")
        let folder = file("docs", path: "cloudreve://my/docs", folder: true)
        let actions = FileActionPolicy.available(targets: [folder], user: user)
        #expect(actions.contains(.pin))
        #expect(actions.contains(.mountPolicy))
        #expect(!actions.contains(.versions))
        #expect(!actions.contains(.directLink), "目录直链需要 folder_direct_link 权限")
    }

    @Test func trashItemsOfferRestoreButNotMove() {
        let user = user(permissionBase64: "AA==")
        let trashed = file(
            "old.txt",
            path: "cloudreve://trash/old.txt",
            metadata: [MetadataKeys.shared.RESTORE_URI: "cloudreve://my/old.txt"]
        )
        let actions = FileActionPolicy.available(targets: [trashed], user: user)
        #expect(actions.contains(.restore))
        #expect(actions.contains(.delete))
        #expect(!actions.contains(.move))
        #expect(!actions.contains(.share))
    }

    @Test func multipleSelectionHidesSingleTargetActions() {
        let user = user(permissionBase64: "AA==")
        let targets = [file("a.txt", path: "cloudreve://my/a.txt"), file("b.txt", path: "cloudreve://my/b.txt")]
        let actions = FileActionPolicy.available(targets: targets, user: user)
        #expect(actions.contains(.move))
        #expect(actions.contains(.delete))
        #expect(!actions.contains(.rename))
        #expect(!actions.contains(.info))
    }

    @Test func symbolicLinkOffersGoToSharedLink() {
        let user = user(permissionBase64: "BA==")
        let link = file(
            "shared.pdf",
            path: "cloudreve://my/shared.pdf",
            metadata: [MetadataKeys.shared.SHARED_REDIRECT: "cloudreve://AbCd@share/shared.pdf"]
        )
        let actions = FileActionPolicy.available(targets: [link], user: user)
        #expect(actions.contains(.goToSharedLink))
        #expect(!actions.contains(.share))
        #expect(!actions.contains(.versions))
    }

    @Test func unimplementedHooksAreFilteredOut() {
        let filtered = FileFeatures().filter([.rename, .download, .share, .open, .versions])
        #expect(filtered == [.rename])
    }

    @Test func iconThemeMatchesExtensionsCaseInsensitively() {
        let theme = FileIconTheme(settings: FileIconTheme.defaultSettings, siteUrl: "https://cloud.example.com")
        #expect(theme.setting(for: file("photo.PNG", path: "cloudreve://my/photo.PNG"))?.icon == "image")
        #expect(theme.setting(for: file("unknown.zzz", path: "cloudreve://my/unknown.zzz")) == nil)
        #expect(theme.setting(for: file("photos", path: "cloudreve://my/photos", folder: true)) == nil)
        #expect(FileIconTheme.symbol(for: "pdf") == "doc.richtext")
    }

    @Test func iconImageURLResolvesAgainstSite() {
        let theme = FileIconTheme(settings: [], siteUrl: "https://cloud.example.com/")
        let png = FileIconSetting(exts: ["x"], icon: nil, color: nil, colorDark: nil, img: "/static/icon.png")
        let svg = FileIconSetting(exts: ["y"], icon: nil, color: nil, colorDark: nil, img: "/static/icon.svg")
        #expect(theme.imageURL(png)?.absoluteString == "https://cloud.example.com/static/icon.png")
        #expect(theme.imageURL(svg) == nil, "SVG 无法用系统图片解码")
    }

    @Test func hexColorParsing() {
        #expect(FileIconTheme.color(hex: "#f44336") != nil)
        #expect(FileIconTheme.color(hex: "#abc") != nil)
        #expect(FileIconTheme.color(hex: "80FF0000") != nil)
        #expect(FileIconTheme.color(hex: "#12") == nil)
        #expect(FileIconTheme.color(hex: nil) == nil)
    }

    @Test func formatHelpers() {
        #expect(FileFormat.folderTitle("cloudreve://my/a/b") == "b")
        #expect(FileFormat.ext(file("Report.Final.PDF", path: "cloudreve://my/Report.Final.PDF")) == "pdf")
        #expect(FileFormat.ext(file("dir.d", path: "cloudreve://my/dir.d", folder: true)) == "")
        let trashed = file(
            "old.txt",
            path: "cloudreve://trash/old.txt",
            metadata: [MetadataKeys.shared.RESTORE_URI: "cloudreve://my/docs/old.txt"]
        )
        #expect(FileFormat.restoreLocation(trashed)?.hasSuffix("/docs") == true)
        #expect(FileFormat.collectCountdown(trashed) == nil)
    }
}
