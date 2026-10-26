# 开发状态

当前基础应用已可运行，核心逻辑与首批页面已接入；完整业务闭环仍在开发，尚未发布稳定版本。

## 平台与结构

- Kotlin 2.4.21、Compose Multiplatform 1.12.2、Miuix 0.9.4。
- Android 13+；共享UI与业务逻辑位于[shared](<../shared/build.gradle.kts>)。
- iOS入口使用SwiftUI与静态Shared.framework，详见[iOS构建说明](<../iosApp/README.md>)。
- JVM仅用于宿主机测试，不作为桌面产品发布。[公用架构](<architecture.md>)说明正常业务与版本边界。

## Standard / Pro

默认`standard`；显式`-PbetterZslfEdition=pro`选择Pro。应用源码保留在本仓，按edition编译；Pro CI是测试/文档仓，不维护源码fork。

| 范围 | Standard | Pro |
| --- | --- | --- |
| 任务、成绩、历史、服务器任务地图 | 保留 | 保留 |
| 真实GPS记录、恢复及显式人工流程 | 保留 | 保留 |
| 热水器、路线导入/手绘/补里程/自动规划 | 裁去产品能力 | 保留，专项文档移至Pro CI |
| Android标识/名称 | `io.github.betterzslf` / `better-zslf` | `io.github.betterzslf.pro` / `better-zslf-pro` |
| 生产跑步上传门 | 保持关闭 | 不因Pro自动开启 |

Pro开发、操作与专项验收见[Pro文档](https://github.com/better-zslf/pro-ci/blob/main/docs/development.md)、[热水器](https://github.com/better-zslf/pro-ci/blob/main/docs/water.md)、[路线工具](https://github.com/better-zslf/pro-ci/blob/main/docs/routes.md)。Pro CI为私有仓，链接需相应仓库访问权限。

2026-10-26本轮只整理公开文档与版本契约，未执行构建、CI或原生测试；不把下列历史结果写成edition拆分后的新结果。

## 已公开的实现与验证状态

以下数字对应已验证提交`9e6ccbca245d103d51e1e0ca720fd8cff43a2957`及其公开历史，属于当时单版本集成。

| 范围 | 实现情况 | 验收情况 |
| --- | --- | --- |
| 工程、主题、四Tab与导航 | 已接入；独立返回栈与序列化路由 | 旧Android Debug/lint通过；本机横竖屏NavigationRail及四标签独立历史通过 |
| 网络、会话与业务API | 双base、信封、401清理、参数校验和上传基础 | 旧离线测试通过；16个登录/只读请求通过，体测场次已知服务端错误单列 |
| 登录、首页、个人资料、设置 | 首版UI已接线；密码不保存 | 模拟器登录、Tab、主题及加密会话重启恢复通过；真实只读接口通过 |
| 资讯、通知、发现、校园网 | 分页、点赞、已读、本机历史隐藏及发现树 | 离线控制器/契约通过；完整UI回归待补 |
| 正常校园跑 | 任务/成绩/历史查询、服务器地图、真实采样会话协调、原生定位与恢复队列 | 会话恢复离线通过；真机GPS、真实任务地图/marker和线上接受性待独立验收。旧导入地图验证只归Pro场景 |
| 场馆与体测 | Lazy查询、表单、最终确认、顺序提交及附件回执核对 | 28项离线通过；未对生产执行预约/报名/上传；场馆取消无已证接口 |
| iOS | Keychain、WebView、文件选择、定位及惰性原生能力actual | framework、Xcode Simulator app、真实CF/Keychain与跨进程恢复通过，Login已人工视觉核对；真机/学校登录/其余交互待验 |
| CI与发版 | 主仓仅build/release，测试工作流归Pro CI的分仓契约已确定 | 旧CI16构建及Native smoke成功；新双仓工作流、签名/环境配置、edition产物及正式发版另验 |

## CI认证与固定来源

跨仓配置已选GitHub App，不再要求个人PAT。Source/Pro两仓分别使用Repository Variable `CI_APP_CLIENT_ID`与Repository Secret `CI_APP_PRIVATE_KEY`（GitHub App PEM，非Android PKCS12），原`release` Environment的五项Android签名secret保留。每个跨仓job独立现签单仓installation token，约1小时、job末默认revoke，不跨job复用；无需服务器或Webhook。

Source只维护标准版build/release；完整测试统一在Pro CI的canonical [tests.yml](https://github.com/better-zslf/pro-ci/blob/main/.github/workflows/tests.yml)。应用源码固定为S，Pro控制器固定为C；Pro tag指向C，Pro产物metadata中的Source SHA仍绑定S，已有tag/release不覆盖。Source手工`publish=false`用40位`source_ref`测试，不伪造正式tag/release。[App配置与权限](https://github.com/better-zslf/pro-ci/blob/main/docs/ci.md)说明每job范围与签名检查职责。

维护者已确认App JWT身份及installation元数据，两仓App Variable/Secret已真配置；两个singleRepo token分别只见唯一Pro CI（Actions写/Metadata读）与唯一Source（Contents读/Metadata读），读源main ref和Pro活跃workflow通过，测试token已revoke。未枚举App全installation仓库名单；新App完整CI及Native/自动发行仍需实际run，本轮文档不代执行或外推产品验收。

## 正常跑步保留的流程

查询任务、成绩和历史，查看服务端提供的任务地图/推荐路径；没有真实任务时不补造任务数据。用户显式开启真实GPS记录，按权限与定位状态处理暂停/继续/结束；恢复不自动启动定位或沿用旧进程确认。结束冻结实测样本，零/单点记录保留，不为补足距离生成点。

本机真实记录、队列和人工界面不等于线上发送已启用。生产`runFactory=null`等门仍保留；当前`RunRuntime`上传工厂默认null，生产graph也显式保留`transportFactory=null`。即使后续开放，也需账号绑定、批次预览、最终确认与明确调用，不由Pro属性或几何工具替代。

## 历史检查口径

旧集成报告627个JUnit方法、零失败/错误/报告跳过，为626离线与1默认关闭Live门控；门控不算真实接口测试。Android App单元任务为NO-SOURCE，不另加数量。A/B/C文案805/720/91断言及scripts217+iOS诊断67共284个不同宿主方法属于对应旧记录，不重复计为Native场景。这些不是本轮Standard/Pro重跑结果。

[验证记录](<testing.md>)保留日期、iOS CI12/14/15失败与CI16成功及待验边界；Pro专项记录迁至[Pro验证历史](https://github.com/better-zslf/pro-ci/blob/main/docs/testing.md)。[iOS脚本说明](<../iosApp/README.md#验证状态>)只解释覆盖范围，实际执行状态仍以各次记录为准。

## 验证原则

- 默认测试不访问学校服务；真实接口另以显式开关及临时环境凭据执行，不纳入普通CI。
- 账号实测限已说明的登录/查询，不用空参试探改密、报名、预约或运动写接口。
- 公用报告不输出身份字段、请求参数、token或响应正文。
- 离线、宿主、模拟器、真实学校任务及真机GPS分别记录；Pro实体BLE也另验。
- ignored分析输入、原始抓包、账号记录、私钥与本机工具链不纳入公用文档或CI仓。
