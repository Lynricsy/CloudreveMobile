import Foundation
import Testing

/// 构建产物中两个扩展的配置：扩展是独立进程、按自己的 Info.plist 执行 ATS，
/// 主 App 能登录的 HTTP 自托管站点在分享上传与系统“文件”中也必须可用
struct ExtensionConfigurationTests {
    @Test(arguments: ["ShareExtension", "FileProviderExtension"])
    func extensionAllowsSelfHostedHttpSites(_ name: String) throws {
        let plugins = try #require(Bundle.main.builtInPlugInsURL, "测试宿主不是主 App")
        let bundle = try #require(Bundle(url: plugins.appending(path: "\(name).appex")), "主 App 未嵌入 \(name)")
        let ats = try #require(bundle.object(forInfoDictionaryKey: "NSAppTransportSecurity") as? [String: Any], "\(name) 没有 ATS 配置")
        #expect(ats["NSAllowsArbitraryLoads"] as? Bool == true)
    }
}
