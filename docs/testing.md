# 验证记录

## 2026-10-23：首次集成

- Kotlin 2.4.21 / Compose Multiplatform 1.12.2 / Miuix 0.9.4。
- Gradle 9.6.1、JDK 21、AGP 9.4.1、compileSdk 37.2、targetSdk 36、minSdk 33。
- `:shared:jvmTest :androidApp:assembleDebug :androidApp:lintDebug` 首次全量通过。
- 初次 JUnit 报告共 112 个方法；接入 BLE adapter 后复跑为 132 个方法、零失败/错误，即 131 项离线测试及 1 个默认关闭的真实接口门控。门控不计作真实测试。
- 独立显式开启的真实接口测试通过：16 次登录/查询请求，服务端体测场次返回已知业务错误 400；其余查询成功。不包含预约、报名、密码、跑步成绩等写操作。
- 测试输出只记录 endpoint/code/count，不记录账号、身份资料、token、参数或响应正文。
- 最后复跑 `:shared:jvmTest :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:lintDebug` 成功。Android App 单元测试任务当前为 NO-SOURCE，不算作额外测试。
- Android lint 零错误、一个警告：targetSdk 未取最新，当前仍为 36。数据迁移规则警告已修复并复核；云备份与设备迁移均排除应用数据。

## 本机模拟器

Android API 36 x86_64，768×1280，WHPX 运行时自检可用，未改变 Windows 功能设置。已安装 Debug APK 并冷启动成功（约 1.9 秒）；登录、四 Tab、校园跑空态、设置二级导航、浅色/深色主题切换及进程重启后的加密会话恢复均通过。退出登录及退出后重启仍未登录也通过（使用全新 UI dump 排除旧文件误判）。发现并修复手动深色主题下原生状态栏图标不可读的问题，最后构建重新安装后已截图复核。期间 AndroidRuntime 错误日志为空。

模拟器不提供真实 GPS 运动或水控器 GATT 兼容性结论。

## 默认离线命令

```bash
bash ./gradlew :shared:jvmTest :androidApp:assembleDebug :androidApp:lintDebug
```

真实接口测试只在同时提供 `ZSLF_LIVE_TEST=1`、`ZSLF_USERNAME`、`ZSLF_PASSWORD` 的临时环境中执行。不要把真实值写进命令文件、配置、Issue 或 CI secrets 的常规测试配置。普通 CI 不访问学校服务。

## 尚待验收

iOS 原生编译/运行、真机 GPS/BLE、完整预约/体测写闭环、真实跑步任务与导入上传、发布签名与 GitHub Actions 实际执行。离线通过不代表这些项目已经完成。
