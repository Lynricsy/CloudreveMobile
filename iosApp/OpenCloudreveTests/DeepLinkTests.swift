import Foundation
import Testing
import OpenCloudreveCore
@testable import OpenCloudreve

/// 深链识别：分享链接交给分享浏览，OAuth 回调与其它 URI 不处理；识别后的文本能被共享层解析
@MainActor
struct DeepLinkTests {
    @Test func recognizesShareDeepLinks() throws {
        let open = try #require(URL(string: "opencloudreve://open?url=https%3A%2F%2Fcloud.example.com%2Fs%2FAbCd%2Fpw1"))
        #expect(ShareLinkDeepLink.isShareLink(open))
        #expect(ShareLinkDeepLink.isShareLink(try #require(URL(string: "cloudreve://AbCd@share"))))
        #expect(ShareLinkDeepLink.isShareLink(try #require(URL(string: "cloudreve://AbCd:pw1@share/sub"))))
    }

    @Test func ignoresOAuthCallbackAndOtherUris() throws {
        #expect(!ShareLinkDeepLink.isShareLink(try #require(URL(string: "opencloudreve://oauth/callback?code=x&state=y"))))
        #expect(!ShareLinkDeepLink.isShareLink(try #require(URL(string: "cloudreve://my/文档".addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""))))
        #expect(!ShareLinkDeepLink.isShareLink(try #require(URL(string: "cloudreve://share"))))
        #expect(!ShareLinkDeepLink.isShareLink(try #require(URL(string: "https://cloud.example.com/s/AbCd"))))
    }

    @Test func sharedParserReadsDeepLinkForms() throws {
        let fromOpen = try #require(ShareLinkParser.shared.parse(input: "opencloudreve://open?url=https%3A%2F%2Fcloud.example.com%2Fs%2FAbCd%2Fpw1"))
        #expect(fromOpen.id == "AbCd")
        #expect(fromOpen.password == "pw1")
        #expect(fromOpen.siteUrl == "https://cloud.example.com")
        let fromUri = try #require(ShareLinkParser.shared.parse(input: "cloudreve://AbCd:pw1@share"))
        #expect(fromUri.id == "AbCd")
        #expect(fromUri.password == "pw1")
        #expect(fromUri.siteUrl == nil)
    }
}
