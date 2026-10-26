# 公用架构与双版本边界

## 唯一源码与双仓交付

本仓保存Kotlin/Compose共享UI、业务与各平台实现；默认构建`standard`，显式`-PbetterZslfEdition=pro`选择Pro。实现代码保留，按edition编译选择，不为分版本维护源码fork。

主仓只承担build/release工作流；[Pro CI](https://github.com/better-zslf/pro-ci)承接测试工作流、Pro发布和专项文档，取上游发布tag并核验固定40位源码SHA。配置约定见[CI说明](https://github.com/better-zslf/pro-ci/blob/main/docs/ci.md)。新双仓及edition产物尚需自己的实际运行结果，本次文档整理不作已通过声明。

## 分层

- common UI/presenter管理显式用户动作、状态展示与序列化页面路由；四Tab保留独立历史。
- app级graph持有会话、设置、repository和应用生命周期资源；页面重组不成为重复启动任务的理由。
- API/repository层处理业务信封、会话及参数校验；公共发布更新使用独立HTTP客户端，不借学校认证。
- 平台expect/actual负责安全存储、WebView、定位与生命周期等原生能力。JVM用于宿主测试，不发布桌面产品。

## 标准版保留正常校园跑

任务、成绩、历史、服务器任务地图和真实GPS记录均保留；显式人工查询、开始/暂停/继续/结束及恢复流程也保留。标准版不是“去掉全部校园跑”或“只剩离线页面”。

真实定位由平台tracker与`RunSessionCoordinator`协调，按账号归属保存本机样本与状态。coordinator不拥有网络worker。恢复需要本进程重新确认，不自动续采；结束冻结样本集，零/单点记录不补造点。记录和任务查询的存在不等于线上写入已验收。

生产`runFactory=null`的现有门保留；当前`RunRuntime`的上传`transportFactory`默认null，生产graph也显式传入`transportFactory=null`。Pro选择不绕过工厂、账号/会话绑定、精确批次预览、最终确认、明确发送及不确定回执处理。

## 仅Pro的功能

热水器及路线导入、手绘、补里程、自动规划/生成预览属于Pro产品能力。Standard裁去这些入口/能力，源码保留在本仓；不要通过移除普通服务器地图或真实GPS实现裁剪。

- [Pro架构](https://github.com/better-zslf/pro-ci/blob/main/docs/architecture.md)
- [热水器开发、操作与验收层级](https://github.com/better-zslf/pro-ci/blob/main/docs/water.md)
- [路线工具、坐标与真实记录隔离](https://github.com/better-zslf/pro-ci/blob/main/docs/routes.md)
- [Pro历史验证与待验清单](https://github.com/better-zslf/pro-ci/blob/main/docs/testing.md)

Pro CI为私有仓，链接需相应访问权限；它不保存应用源码副本或ignored原始分析材料。

## 平台与验收边界

Android Standard标识/名称保持`io.github.betterzslf`/`better-zslf`，Pro使用`io.github.betterzslf.pro`/`better-zslf-pro`。iOS应用标识、签名、真机archive另按平台配置和证据登记，不从Android名称推断。

纯离线、宿主脚本、原生模拟器、真实任务、真机GPS与实体BLE分别留证。旧单版本627等报告包含现已归Pro的子集，不是新双版本测试数量。平台失败历史和普通功能待验事项见[验证记录](<testing.md>)；2026-10-26本轮仅静态文档整理。
