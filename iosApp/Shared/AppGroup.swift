import Foundation

/// App 与扩展共用的容器常量
enum AppGroup {
    static let identifier = "group.plus.ling.opencloudreve"

    static var containerURL: URL {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: identifier)
            ?? FileManager.default.temporaryDirectory
    }
}
