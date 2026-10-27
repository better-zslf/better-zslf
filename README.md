# better-zslf

掌上莲峰的第三方客户端，使用 Kotlin Multiplatform、Compose Multiplatform 与 Miuix 构建 Android / iOS 共享界面。

项目仍在开发。已有源码与预览构建不代表全部业务、实体蓝牙设备和真实跑步任务均已验收；当前进度见[开发状态](<docs/development.md>)。

## 标准版与 Pro

两版使用同一份应用源码。默认构建标准版；Pro 专属实现从标准版的编译输入中排除，而不是仅隐藏按钮。

| 功能 | 标准版 | Pro |
| --- | --- | --- |
| 登录、资讯、通知、个人资料、校园网、预约与体测 | 保留 | 保留 |
| 校园跑任务、成绩、历史和服务器轨迹地图 | 保留 | 保留 |
| 真实 GPS 采样、本机记录与恢复 | 保留 | 保留 |
| 路线导入、手绘、补里程及本地规划预览 | — | 保留 |
| 热水器蓝牙功能 | — | 保留 |

普通校园跑没有被移除。两版仍保留现有账户隔离、最终确认与发送门控；当前正式界面没有启用真实轨迹上传 transport，Pro 也不会绕过这些门控。热水器与路线工具的使用说明、开发和专项验证记录位于 [pro-ci 文档](<https://github.com/better-zslf/pro-ci/tree/main/docs>)。

Android 标准版应用 ID 为 `io.github.betterzslf`；Pro 为 `io.github.betterzslf.pro`，版本名追加 `-pro`，可分别安装。iOS 使用对应独立 bundle ID，营销版本保持数值格式。

## 构建

需要 JDK 21、Android SDK 37.2 / Build Tools 36.0.0；Android 最低版本为 13。使用仓库的 Gradle wrapper：

```sh
# 标准版（默认）
./gradlew :androidApp:assembleDebug

# Pro；不会覆盖标准版的 applicationId
./gradlew -PbetterZslfEdition=pro :androidApp:assembleDebug
```

切换版本验证编译排除时先清理旧输出。发布签名从临时环境输入，不将 keystore 或密码写入源码。Windows 已安装本机工具链时可先在 PowerShell dot-source `scripts/use-local-tools.ps1`。

iOS 需要 macOS 与所固定的 Xcode 工具链，见 [iOS 工程说明](<iosApp/README.md>)；[架构说明](<docs/architecture.md>)介绍源集与版本边界。

## CI 与发布

- 本仓只维护标准版构建和发布两个工作流。
- 预发布版本保留 `VERSION_NAME` 的数字三段式（供 iOS 营销版本使用），在 `version.properties` 加 `VERSION_PRERELEASE=rc1`，并递增 `VERSION_CODE`。应用显示与标准版 tag 为 `1.0.0-rc1` / `v1.0.0-rc1`，Pro 为 `1.0.0-rc1-pro` / `v1.0.0-rc1-pro`。RC 发布必须标为 prerelease，不替换 stable latest；正式版移除该字段并再次递增构建号。
- 完整测试与Pro发布逻辑统一维护在[pro-ci](<https://github.com/better-zslf/pro-ci>)的canonical [tests.yml](<https://github.com/better-zslf/pro-ci/blob/main/.github/workflows/tests.yml>)及release入口。Source发布成功后显式dispatch，验证published源release及固定源码SHA **S**；Pro控制器另固定为 **C**，其tag指向C而产物metadata的Source SHA仍为S。已有tag/release不覆盖。
- 跨仓使用GitHub App，不再要求两枚个人PAT。两个仓库分别配置Repository Variable `CI_APP_CLIENT_ID`和Repository Secret `CI_APP_PRIVATE_KEY`（GitHub App PEM，非Android PKCS12）；每个job现签单仓最小权限、约1小时的installation token，job末默认revoke，不跨job复用。无需服务器/Webhook，原五项Android签名配置不变。配置和权限分工见[Pro CI说明](<https://github.com/better-zslf/pro-ci/blob/main/docs/ci.md>)。
- Source手工`publish=false`使用40位`source_ref`只测试/构建检查，无需也不创建正式tag/release。两仓App配置及单仓最小权限访问已验证；[App触发的双版Android/iOS验收](<https://github.com/better-zslf/pro-ci/actions/runs/40312777553>)已通过，包含真实Simulator smoke。tests-only不等于正式发行或学校真实业务验收。
- 普通版安装包在正式发版后提供于[本仓 Releases](<https://github.com/better-zslf/better-zslf/releases>)，Pro 位于 [pro-ci Releases](<https://github.com/better-zslf/pro-ci/releases>)。构建成功不等于业务验收全部通过。

## 反馈与隐私

[提交问题或建议](<https://github.com/better-zslf/better-zslf/issues/new/choose>)时附版本、系统及复现步骤。不要在 Issue、日志或截图里填写学号、密码、Token 等身份信息。

API 规范另见 [zslf-API-collect](<https://github.com/better-zslf/zslf-API-collect>)。本项目与学校及掌上莲峰官方无关联。

## License

MIT
