# OpenCloudreve Mobile

[OpenCloudreve](https://github.com/Lynricsy/OpenCloudreve)（Cloudreve v4 社区版个人分支）的 iOS 与 Android 客户端。

- 共享逻辑：Kotlin Multiplatform（网络、模型、数据库、鉴权、传输规划、ViewModel），模块 `:shared`
- Android：Jetpack Compose + Material 3（动态取色、Predictive Back），minSdk 28，targetSdk 36
- iOS：SwiftUI，iOS 26+，Liquid Glass 设计
- 服务端要求：OpenCloudreve ≥ 4.19.11 可使用全部功能（Passkey、网页会话交接、WebView 验证码）；更低版本仍可密码登录、管理文件和上传

## 目录

```
shared/       KMP 共享核心（包 plus.ling.opencloudreve.core）
androidApp/   Android 应用（包 plus.ling.opencloudreve）
iosApp/       iOS 工程（XcodeGen 的 project.yml，App + 分享扩展 + 文件提供程序扩展）
scripts/      本地开发脚本
.github/      CI
```

## 构建

需要 JDK 17+ 与 Android SDK（platform 37、build-tools 36）。在 `local.properties` 写 `sdk.dir=<SDK 路径>`。

```bash
# 共享层单测 + Android 调试包
./gradlew :shared:allTests :shared:testAndroidHostTest :androidApp:assembleDebug

# iOS（仅 macOS，需 Xcode 26 与 xcodegen）
./scripts/build-ios-framework.sh debug
cd iosApp && xcodegen generate && open OpenCloudreve.xcodeproj
```

iOS 工程文件不入库，只提交 `iosApp/project.yml`；`iosApp/Frameworks/OpenCloudreveCore.xcframework` 由上面的脚本从 Gradle 产物复制。

> 说明：Compose BOM 2026.09、OkHttp 5.5 等依赖要求以 API 37 编译，因此 `compileSdk = 37`；`targetSdk` 仍为 36。

## 本地联调

```bash
./scripts/run-server.sh   # 在 /tmp/x 编译并启动服务端，端口 5212
./scripts/emulator.sh     # 无窗口启动模拟器；模拟器内服务器地址填 http://10.0.2.2:5212
```

新库第一个注册的用户是管理员。

## CI

- `android.yml`（ubuntu）：共享层单测、Android 单测、lint、调试 APK（产物 `opencloudreve-debug-apk`）
- `ios.yml`（macos-26）：构建 XCFramework → xcodegen → 模拟器构建与 XCTest，上传 `.xcresult` 与截图（不签名）
