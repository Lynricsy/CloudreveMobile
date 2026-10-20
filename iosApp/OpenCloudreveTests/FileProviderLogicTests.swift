import FileProvider
import Testing
import OpenCloudreveCore
@testable import OpenCloudreve

/// File Provider 的纯逻辑：分页位置编解码、条目标识映射、能力位映射、版本、错误映射、域显示名
struct FileProviderLogicTests {
    // MARK: - 分页

    @Test func initialPagesDecodeAsFirst() {
        #expect(FileProviderPageToken(NSFileProviderPage.initialPageSortedByName) == .first)
        #expect(FileProviderPageToken(NSFileProviderPage.initialPageSortedByDate) == .first)
        #expect(FileProviderPageToken(NSFileProviderPage(Data("garbage".utf8))) == .first)
    }

    @Test func cursorAndPageRoundTrip() {
        let cursor = FileProviderPageToken.cursor("eyJpZCI6MTJ9:abc")
        #expect(FileProviderPageToken(cursor.providerPage) == cursor)
        #expect(cursor.cursor == "eyJpZCI6MTJ9:abc")
        #expect(cursor.pageNumber == nil)

        let page = FileProviderPageToken.page(3)
        #expect(FileProviderPageToken(page.providerPage) == page)
        #expect(page.pageNumber == 3)
        #expect(page.cursor == nil)

        #expect(FileProviderPageToken.first.cursor == nil)
        #expect(FileProviderPageToken.first.pageNumber == nil)
        #expect(FileProviderPageToken(FileProviderPageToken.first.providerPage) == .first)
    }

    @Test func nextPagePrefersCursorThenPageNumber() {
        #expect(FileProviderPageToken.next(nextToken: "t2", hasMore: true, page: 0) == .cursor("t2"))
        #expect(FileProviderPageToken.next(nextToken: "", hasMore: true, page: 1) == .page(2))
        #expect(FileProviderPageToken.next(nextToken: nil, hasMore: true, page: 0) == .page(1))
        #expect(FileProviderPageToken.next(nextToken: nil, hasMore: false, page: 4) == nil)
    }

    @Test func pageNumberTokenMatchesSharedPagination() {
        // 页码模式：第 0 页 50 条、共 120 条 → 还有下一页
        let first = Pagination(page: 0, pageSize: 50, totalItems: KotlinInt(int: 120), nextToken: nil, isCursor: false)
        #expect(FileProviderPageToken.next(nextToken: first.nextToken, hasMore: first.hasMore, page: Int(first.page)) == .page(1))
        let last = Pagination(page: 2, pageSize: 50, totalItems: KotlinInt(int: 120), nextToken: nil, isCursor: false)
        #expect(FileProviderPageToken.next(nextToken: last.nextToken, hasMore: last.hasMore, page: Int(last.page)) == nil)
        // 游标模式最后一页
        let cursorEnd = Pagination(page: 0, pageSize: 50, totalItems: nil, nextToken: nil, isCursor: true)
        #expect(FileProviderPageToken.next(nextToken: cursorEnd.nextToken, hasMore: cursorEnd.hasMore, page: 0) == nil)
    }

    // MARK: - 标识

    @Test func systemIdentifiersMapToTargets() {
        #expect(FileProviderIdentity.target(of: .rootContainer) == .root)
        #expect(FileProviderIdentity.target(of: .trashContainer) == .trash)
        #expect(FileProviderIdentity.target(of: .workingSet) == .workingSet)
        #expect(FileProviderIdentity.target(of: NSFileProviderItemIdentifier("lpUq")) == .file(id: "lpUq"))
    }

    @Test func fileIdentifierIsFileIdExceptRoots() {
        #expect(FileProviderIdentity.identifier(fileId: "root1", uri: "cloudreve://my") == .rootContainer)
        #expect(FileProviderIdentity.identifier(fileId: "root1", uri: "cloudreve://my/") == .rootContainer)
        #expect(FileProviderIdentity.identifier(fileId: "t", uri: "cloudreve://trash") == .trashContainer)
        #expect(FileProviderIdentity.identifier(fileId: "aB3", uri: "cloudreve://my/docs/a.txt").rawValue == "aB3")
        // 他人的“我的文件”根不是本域根
        #expect(FileProviderIdentity.identifier(fileId: "u", uri: "cloudreve://xyz@my").rawValue == "u")
    }

    @Test func parentResolution() {
        #expect(FileProviderIdentity.knownParent(ofURI: "cloudreve://my/a.txt") == .rootContainer)
        #expect(FileProviderIdentity.knownParent(ofURI: "cloudreve://trash/a.txt") == .trashContainer)
        #expect(FileProviderIdentity.knownParent(ofURI: "cloudreve://my/docs/a.txt") == nil)
        #expect(FileProviderIdentity.parentURI(of: "cloudreve://my/docs/a%20b.txt") == "cloudreve://my/docs")
        #expect(FileProviderIdentity.parentURI(of: "cloudreve://my") == nil)
        #expect(FileProviderIdentity.childURI(parentURI: "cloudreve://my/docs", name: "a b#.txt") == "cloudreve://my/docs/a%20b%23.txt")
        #expect(FileProviderIdentity.isInTrash("cloudreve://trash/x"))
        #expect(!FileProviderIdentity.isInTrash("cloudreve://my/x"))
    }

    // MARK: - 能力位

    private func bits(_ values: Int32...) -> (Int32) -> Bool {
        { values.contains($0) }
    }

    @Test func fileCapabilities() {
        let c = NavigatorCapability.shared
        let all = FileProviderCapabilityMap.capabilities(isFolder: false, inTrash: false) { _ in true }
        #expect(all == [.allowsReading, .allowsWriting, .allowsRenaming, .allowsReparenting, .allowsTrashing, .allowsDeleting])

        let readOnly = FileProviderCapabilityMap.capabilities(isFolder: false, inTrash: false, has: bits(c.DOWNLOAD_FILE, c.INFO))
        #expect(readOnly == [.allowsReading])

        let noDownload = FileProviderCapabilityMap.capabilities(isFolder: false, inTrash: false, has: bits(c.RENAME_FILE, c.SOFT_DELETE))
        #expect(noDownload == [.allowsRenaming, .allowsReparenting, .allowsTrashing])
    }

    @Test func folderCapabilities() {
        let c = NavigatorCapability.shared
        let all = FileProviderCapabilityMap.capabilities(isFolder: true, inTrash: false) { _ in true }
        #expect(all == [.allowsContentEnumerating, .allowsAddingSubItems, .allowsRenaming, .allowsReparenting, .allowsTrashing, .allowsDeleting])

        let browseOnly = FileProviderCapabilityMap.capabilities(isFolder: true, inTrash: false, has: bits(c.ENTER_FOLDER))
        #expect(browseOnly == [.allowsContentEnumerating])
        #expect(!browseOnly.contains(.allowsReading))
    }

    @Test func trashCapabilitiesOnlyDeleteAndRestore() {
        let c = NavigatorCapability.shared
        let all = FileProviderCapabilityMap.capabilities(isFolder: false, inTrash: true) { _ in true }
        #expect(all == [.allowsDeleting, .allowsReparenting])
        let restoreOnly = FileProviderCapabilityMap.capabilities(isFolder: true, inTrash: true, has: bits(c.RESTORE))
        #expect(restoreOnly == [.allowsReparenting])
    }

    @Test func capabilitiesFallBackToContainerThenAll() {
        // 位按字节内低位在前：DOWNLOAD_FILE(7) = 0x80，RENAME_FILE(1) = 0x02
        let container = BoolSet.companion.fromBase64(s: "gA==")
        #expect(container.has(bit: NavigatorCapability.shared.DOWNLOAD_FILE))
        let fromContainer = FileProviderCapabilityMap.capabilities(isFolder: false, inTrash: false, own: nil, container: container)
        #expect(fromContainer == [.allowsReading])
        let own = BoolSet.companion.fromBase64(s: "Ag==")
        let fromOwn = FileProviderCapabilityMap.capabilities(isFolder: false, inTrash: false, own: own, container: container)
        #expect(fromOwn == [.allowsRenaming, .allowsReparenting])
        let unknown = FileProviderCapabilityMap.capabilities(isFolder: false, inTrash: false, own: nil, container: nil)
        #expect(unknown.contains(.allowsReading) && unknown.contains(.allowsWriting))
    }

    // MARK: - 版本

    @Test func versionsTrackEntityAndMetadata() {
        let a = FileProviderVersion.content(primaryEntity: "e1", updatedAtMillis: 1)
        #expect(a == FileProviderVersion.content(primaryEntity: "e1", updatedAtMillis: 2))
        #expect(a != FileProviderVersion.content(primaryEntity: "e2", updatedAtMillis: 1))
        #expect(FileProviderVersion.content(primaryEntity: nil, updatedAtMillis: 5) != FileProviderVersion.content(primaryEntity: nil, updatedAtMillis: 6))
        #expect(FileProviderVersion.metadata(name: "a", updatedAtMillis: 1) != FileProviderVersion.metadata(name: "b", updatedAtMillis: 1))
    }

    // MARK: - 错误

    @Test func errorCodesMapToFileProviderErrors() {
        let e = ErrorCode.shared
        #expect(FileProviderErrorMap.code(forErrorCode: e.LOGIN_REQUIRED) == .notAuthenticated)
        #expect(FileProviderErrorMap.code(forErrorCode: e.CREDENTIAL_INVALID) == .notAuthenticated)
        #expect(FileProviderErrorMap.code(forErrorCode: e.FILE_NOT_FOUND) == .noSuchItem)
        #expect(FileProviderErrorMap.code(forErrorCode: e.PARENT_NOT_EXIST) == .noSuchItem)
        #expect(FileProviderErrorMap.code(forErrorCode: e.OBJECT_EXIST) == .filenameCollision)
        #expect(FileProviderErrorMap.code(forErrorCode: e.INSUFFICIENT_CAPACITY) == .insufficientQuota)
        #expect(FileProviderErrorMap.code(forErrorCode: e.TRANSPORT) == .serverUnreachable)
        #expect(FileProviderErrorMap.code(forErrorCode: e.LOCK_CONFLICT) == nil)
    }

    @Test func swiftErrorsMap() {
        #expect((FileProviderErrorMap.providerError(CancellationError()) as NSError).code == CocoaError.userCancelled.rawValue)
        let offline = FileProviderErrorMap.providerError(URLError(.notConnectedToInternet)) as NSError
        #expect(offline.domain == NSFileProviderErrorDomain)
        #expect(offline.code == NSFileProviderError.serverUnreachable.rawValue)
    }

    // MARK: - 域

    @Test func domainDisplayName() {
        #expect(FileProviderDomains.displayName(nickname: "小明", email: "a@b.c", host: "cloud.example.com") == "小明 @ cloud.example.com")
        #expect(FileProviderDomains.displayName(nickname: " ", email: "a@b.c", host: "h") == "a@b.c @ h")
    }
}
