# mebot

[English](README.en.md)

![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-2563EB)
![Room](https://img.shields.io/badge/Room-Local%20Storage-16A34A)
![probe-vps](https://img.shields.io/badge/probe--vps-Optional-2563EB)

mebot 是 Android 个人工具应用。首页汇总待办、日程和需要关注的事项。日程、闪念、订阅、经期和档案等核心记录保存在手机上。交易页可连接行情及只读账户接口。需要持续盯价和手机推送时，可自行搭建本仓库的 `probe-vps`。

## 功能

- **首页、日程和闪念**　查看今天的事项，固定或轮换闪念，保存草稿与附件。日程支持手动编辑，并在授权后由系统日历发送准点和提前提醒。
- **经期与订阅**　在手机上管理经期记录、周期统计、订阅账单和扣费历史。相关提醒可在首页查看。
- **档案**　整理本地文字、图片和文件。支持文字查找，可按需配置模型提取与语义检索。
- **交易工作台**　查看币安 U 本位合约 K 线、只读持仓与成交，保存分组、画线、趋势理由、交易笔记和仓位计划。连接 `probe-vps` 后，可接收价格提醒与新闻。
- **外观**　切换主题、应用图标和触觉反馈。可配置首页文本分类，让输入交给对应的 Bot。

## 环境与构建

安装 Android SDK 36，用 Android Studio 打开仓库根目录，或为 Gradle 提供 JDK 17 及 Android SDK。设备需要 Android 8.0（API 26）或更高版本。

```sh
./gradlew :app:assembleDebug
```

调试 APK 生成在 `app/build/outputs/apk/debug/app-debug.apk`。可用 Android Studio 运行，或使用 Android SDK 中的 `adb` 安装到已授权调试的设备。

## 配置

| 功能 | 配置方式 |
| --- | --- |
| 模型与语义检索 | 在 App 的「设置 → 模型与 API」填写所需模型接口和密钥。 |
| 币安账户 | 在交易系统的连接设置中填写只读 API 凭据。公开行情可以独立使用。 |
| 价格提醒、新闻与推送 | 运行 [probe-vps](probe-vps/README.md)，按需启用新闻采集和 Firebase Cloud Messaging。在 App 的交易设置中填写提醒服务的 HTTPS 地址与访问令牌。 |
| Android 构建可选默认值 | 从 [local.properties.example](local.properties.example) 查看 Android SDK 路径、决策接口和提醒服务地址的示例。 |

需要 FCM 时，在你自己的 Firebase 项目注册包名 `com.txl.mebot`，将 Android 客户端配置保存到 `app/src/debug/google-services.json` 后重新构建。服务账号文件仅用于你自己的提醒服务。服务端参数、HTTPS 及本地联调见 [API 文档](probe-vps/API.md)。

## 开发与验证

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
python3 -B -m unittest discover -s probe-vps -p 'test_*.py'
```

Room 的历史结构保存在 `app/schemas/`。更新结构时应添加保留用户数据的迁移。工程设计和实现约定见 [开发约束](AGENTS.md) 与 [设计文档](docs/design/home-ui.md)。

## 隐私与安全

核心业务记录及附件保存在本机。启用模型、行情、币安账户、价格提醒或新闻时，相应功能按需连接配置的服务。Android 配置、私钥、访问令牌、数据库、日志及构建产物由 Git 忽略。公开仓库提供源码及占位配置，可使用自己的服务和凭据运行。

## 许可证

项目主要面向个人、非商业使用。应用源码采用 [MIT 许可证](LICENSE)，`probe-vps` 保留其 [MIT 许可证及上游署名](probe-vps/LICENSE)。打包的第三方图表资源保留 [原有许可与署名](app/src/main/assets/trading/NOTICE.txt)。
