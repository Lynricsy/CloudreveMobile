# 回归清单（smoke）

每次发版前按本清单做一次**全新安装、全新账号**的回归。截图统一存到 `/tmp/ocr-shots/e2e/`，每一步都要打开截图看一眼。

## 一、环境

| 项 | 说明 |
|---|---|
| 服务端 | OpenCloudreve ≥ 4.19.11，`scripts/run-server.sh` 启动；宿主 `http://127.0.0.1:5212`，模拟器 `http://10.0.2.2:5212`（站点 `siteURL` 设为后者，签名下载链接才能在模拟器内用） |
| 管理员 | 首个注册用户；改站点设置用 `PATCH /api/v4/admin/settings {"settings":{...}}`，测完改回原值 |
| 分片上传 | 新建一个 local 策略（`chunk_size` = 5 MB）和使用它的用户组（开启 WebDAV 权限），把测试账号移进去；**不要改 Admin/User 组** |
| MinIO（可选） | S3 策略分片 + ETag + 回调：`docker run minio/minio`，配一个 S3 策略（path style） |
| aria2（可选） | 离线下载：aria2 容器接入主节点 |
| Passkey 指纹 | 站点设置 `mobile_android_sha256_fingerprints` 填 debug keystore 的 SHA256（`keytool -list -v -keystore ~/.android/debug.keystore -storepass android`），重启服务端；`GET /.well-known/assetlinks.json` 应返回该指纹 |
| 模拟器 | `scripts/emulator.sh`（AVD `ocr`，android-34 google_apis x86_64，无 Play 商店、无 Google 账号） |
| 安装 | `adb uninstall plus.ling.opencloudreve && adb install androidApp/build/outputs/apk/debug/androidApp-debug.apk` |
| TOTP | 用 secret 按 RFC 6238（SHA1、30 秒、6 位）计算，任意脚本即可 |

## 二、Android

| # | 步骤 | 预期 |
|---|---|---|
| 1 | 全新安装 → 服务器页输入地址 → “创建账户”注册新账号 → 临时打开 `login_captcha=1`、`captcha_type=normal`，回到服务器页重新进入后登录 | 注册后停在密码页且邮箱已预填；normal 验证码图片可点按刷新，输错提示“验证码错误”并换图；输对进入“文件”首页；**登录后不应残留软键盘**。测完把 `login_captcha` 改回 0 |
| 2 | 我的 → 设置 → 账户安全 → 两步验证：扫码/抄 secret，输入 TOTP 开启 → 登出 → 密码登录 | 开启后显示“已开启”；再登录进入 6 位验证码页，输满自动提交并进入首页，无键盘残留 |
| 3 | 把账号移到 5 MB 分片组；上传 300 MB 文件；上传中 `adb shell input keyevent 26` 锁屏，完成后解锁 | 传输列表“已完成”；通知显示进度；**当前目录列表自动出现该文件（无需下拉刷新）**；服务端文件 `sha256sum` 与本地一致 |
| 4 | 文件 → 分享：私密 + 自定义密码 + 7 天 → 创建；`curl /api/v4/share/info/<id>?password=` 与 `POST /file/url` 匿名下载；另一个账号在 App 内“打开分享链接”（先不带密码）→ 输密码 → “保存到我的文件” | 链接含密码、二维码可见；匿名下载 sha256 一致；不带密码时出现密码页；保存后对方“我的文件”出现指向该分享的快捷方式（`sys:shared_redirect`，大小显示 0 属服务端行为） |
| 5 | 预览 mp4（同目录同名 srt）、mp3、pdf、md；md 进入编辑追加一行保存后返回列表 | 视频播放并显示字幕、字幕列表含 srt；音乐显示标题/艺术家、通知栏媒体会话；PDF 分页渲染；md 渲染 + 保存成功，服务端内容更新，**返回列表后大小/时间已更新** |
| 6 | 删除 → 回收站显示原路径与清理倒计时 → 还原 → 再删除 → 回收站“清空回收站” | 还原后回到原目录；清空后回收站为空 |
| 7 | 设置 → WebDAV → 新建账户（组需有 WebDAV 权限）→ 复制密码；`curl -u <邮箱>:<密码> -X PROPFIND -H 'Depth: 1' http://127.0.0.1:5212/dav/` | 列表出现新账户；PROPFIND 返回 207 并列出根目录文件 |
| 8 | 账户安全 → 在本设备添加 Passkey | 有凭据提供方的设备上注册成功并出现在列表；无 Google 账号的模拟器只会弹出“在其他设备上创建”二维码（记为无法验证） |
| 9 | 系统“文件”应用：侧栏出现每个账户的根；打开一个云端文件；从“下载”复制一个文件到云端根；在“文件”应用里给某个目录改名，再打开改名前打开过的其中文件；用外部编辑器打开云端文本，断网后清空并保存，再联网 | 根目录列出云端文件；文件能被外部应用打开；复制后服务端出现同名文件且大小一致；父目录改名后子文件仍能打开（文档 ID 为服务端文件 ID）；清空保存出现在传输列表，联网后服务端变为空文件 |
| 10 | 管理员账号：我的 → 管理：概览、用户列表/详情、“在网页后台打开” | 概览图表与统计正常；用户可筛选、编辑；网页后台免登录打开；普通账号看不到管理入口 |
| 11 | 系统分享菜单把一张图片分享给 OpenCloudreve → 选账户与目录上传；`adb shell am start -a android.intent.action.VIEW -d 'opencloudreve://open?url=<分享链接>'` | 上传任务完成、文件出现；深链直接打开分享浏览页 |

可选：MinIO S3 策略上传（分片、ETag、完成、回调、取消后占位消失）；进程被杀（`adb shell am kill plus.ling.opencloudreve`）后上传续传；下载中断网后 Range 续传；aria2 离线下载进度与文件勾选。

### 已知的模拟器限制

- `google_apis` 镜像无 Google 账号：Passkey 只能走跨设备二维码，无法在本机完成。
- 该镜像自带的 Google 相册打开本地或云端视频都会一直转圈，不能用它验证“文件”应用里的音视频打开，换用自带解析器的播放器或真机验证。

## 三、iOS

本机没有 Xcode，iOS 以 CI 为准：`gh workflow run ios.yml`，下载 `.xcresult` 与截图产物检查。真机才能验证的行为（后台 URLSession、Share Extension、File Provider、Passkey/网页登录、锁屏播放控制等）见 `docs/manual-qa-ios.md`。

CI 已覆盖的 UI 测试类（`iosApp/OpenCloudreveUITests/`，`TestServerAPI.swift` 为测试服务端辅助）：

- `LoginFlowUITests`：服务器页、登录、验证码、两步验证、账户切换
- `FileBrowserUITests`：文件列表、网格/列表、多选、长按菜单、新建与重命名
- `TransferUITests`：上传与传输列表
- `ViewerUITests`：各内置预览器
- `ShareTasksUITests`：分享管理与后台任务
- `SettingsAdminUITests`：设置各页与轻量管理
- `UIPolishUITests`：深链、实时刷新、iPad 布局、英文/深色/大字号
