# WorkBuddy 桌面客户端的 Ardot 集成 — 逆向技术实现报告

**分析对象**：WorkBuddy 桌面客户端（Electron）
**安装目录**：`C:\Users\bx233\AppData\Local\Programs\WorkBuddy`
**主包**：`resources\app.asar`（96,544,269 B，14474 个文件条目）
**解包目录**：`resources\app.asar.unpacked`（renderer / cli / main / node_modules 大部分是 unpacked）
**分析方式**：只读。解析 asar pickled header 得到「文件路径 → 绝对偏移」映射，再按文件精确切片；辅以 `app.asar` 全局字节 grep 与运行时目录 `C:\Users\bx233\.workbuddy` 的只读核对。
**版本**：`5.8.3-wb.41334564.gdb0e9b51.h4da087921e4a`

> **阅读须知 — 偏移量语义**
> 所有 `abs=NNNN` 都是 **app.asar 文件的绝对字节偏移**（等价于 `dd if=app.asar bs=1 skip=N`）。
> 文中同时给出 `rel=NNNN`（该文件内的相对偏移）与文件路径，便于复现。
>
> asar 不是压缩包，正文是 UTF-8 明文；JS 里大量使用反引号模板字符串，所以 `ardot.getToken` 这类字符串是**代码里的字面量**，不是二进制噪声。

---

## 0. 结论速览（TL;DR）

反查你给的线索前，先说最重要的一条纠正：

> ## ⚠️ `ardot.getToken` / `ardot.fetchAccessToken` / `ardot.mcpConnect` **不是** IPC channel
>
> 它们是 `withTimeout(fn, ms, label)` 的**超时标签字符串**，用于诊断日志与 `TimeoutError` 的 message。
> 真正的跨进程 RPC 前缀是 `mcpApps:*`（renderer ↔ daemon 主进程）。

证据（`/main/app-server-mcp-apps-host.js`，`abs=75070496` / `75070612` / `75069550`）：

```js
// abs=74992546  rel=4154
async function fetchArdotAccessToken(e,t,n,i){ ... }

// abs=75070496 ~ 75070700（ensureUnknownAppReady 内）
let{accessToken:e}=await r.Rt(e=>fetchArdotAccessToken(m,void 0,e),$,`ardot.fetchAccessToken`);
n.Authorization=`Bearer ${e}`;
...
let t=await r.Rt(()=>e.mcpManager.connect(p),$,`ardot.mcpConnect`);
```

`r.Rt` 的导出映射（`/main/server-side-access-token.js`）：

```js
Object.defineProperty(exports,"Rt",{enumerable:!0,get:function(){return withTimeout}})
Object.defineProperty(exports,"Lt",{enumerable:!0,get:function(){return TimeoutError}})
```

`withTimeout` 本体（`abs=85970000` 附近，`server-side-access-token.js`）：

```js
var TimeoutError=class extends Error{constructor(e,t){super(`operation "${e}" timed out after ${t}ms`),
this.label=e,this.timeoutMs=t,this.name=`TimeoutError`}};
function withTimeout(e,t,n){let r=new AbortController,i,
a=new Promise((e,a)=>{i=setTimeout(()=>{r.abort(),a(new TimeoutError(n,t))},t),i.unref?.()});
return Promise.race([e(r.signal),a]).finally(...)}
```

第三参数 `n` 就是那串 `ardot.mcpConnect` —— **纯标签**。
`$` = `1e4`（10s 超时），见 `var $=1e4;`（`apps-host.js` rel≈76300）。

**同时**：`ardot.getToken` / `ardot.fetchAccessToken` 这两个名字**确实也出现在真实 IPC 通道表里**，但形态是 `mcpApps:getToken`，见 §3.2。这是两套东西，别混。

---

## 1. 分层总览：WorkBuddy 侧一共花了哪几层

| 层 | 组件 | 关键实现 | 证据 |
|---|---|---|---|
| **L1 进程层** | Electron main(daemon) + renderer + CLI worker | 主进程内 `DaemonServer` + `app-server-mcp-apps-host`；MCP 客户端跑在 **daemon** 侧；renderer 只是 UI | `apps-host.js`、`server.js` |
| **L2 MCP 层** | `FirstPartyMcpProvider` + `RemoteMcpClientController` + `SdkMcpRemoteTransport` | 内置 first-party server `ardot` → `https://ardot.tencent.com/mcp`，`type:"http"`；**先 streamable-http，失败回退 SSE** | `server.js` rel=764406 / 737878 |
| **L3 鉴权层** | `ArdotManager` + `FirstPartyMcpProvider.managedHeaders` + connector-OAuth 后端代理 | **不做 MCP OAuth**。走 WorkBuddy 自己的 `connector/oauth/<id>/{status,connect,accesstoken,revoke}` 影子账号代理，拿 `access_token` 后作为 `Authorization: Bearer` 注入 | `server.js` rel=2581418 |
| **L4 UI 面板层** | MCP Apps（`ui://ardot-remote/editor.html`）+ 双层 iframe | 服务端下发 HTML（≈634KB），host 起本地 HTTP server `/mcp-apps/<instance>/app.html`，外层 sandbox-proxy iframe + 内层 app iframe，`postMessage` JSON-RPC 桥 | `apps-host.js`、`origins.js`、`/resources/mcp-app-preload.js` |
| **L5 技能层** | 内置插件包 `ardot`（7 个 skill）+ prompt 注入段落 | **打包在 asar 里**（`builtin-plugins/ardot/skills/**`），不走服务端下发；另有每轮注入的 `<ardot_file_directive>` / `<ardot_design_style>` / `<ardot_image_gen>` / `<ardot-design-skill-requirement>` | asar 文件条目、`tar.js`、`daemon-app-server-main.js` |

**一句话架构**：
> Ardot 编辑器 = 一个**服务端下发的 MCP App HTML**，被 WorkBuddy 用「本地静态服务 + 双 iframe + JSON-RPC postMessage 桥」跑起来；
> 它需要的设计工具 = **内置 first-party MCP server**（`ardot`），transport 是 **streamable HTTP**，鉴权 = **WorkBuddy 账号体系代理拿到的 Bearer token**（不是 MCP 标准 OAuth/PKCE）；
> 模型看到的工具名 = **`mcp__ardot__<tool>`**，UI/日志里另有一套 `ardot_<tool>` 扁平别名。

---

## 2. MCP 连接管理

### 2.1 server 注册：内置 first-party，不写 mcp.json

`FirstPartyMcpProvider` 是内存态 provider，`collect()` 直接吐 items。
`/main/server.js` `rel=764406`，`abs=86869169`：

```js
var zl={ardot:`ardot`,netdrive:`netdrive`,"agent-mail":`agent-mail`,
        "genie-baas":`genie-baas`,"space-library":`space-library`},
    Bl=new Set([...Object.values(zl),`calendar`]),
    Vl=`/console/agent-gateway/netdrive/mcp`,
    Hl=`/console/agent-gateway/genie-baas/mcp`,
    Ul=`/console/agent-gateway/agentmail/mcp`,
    Wl=`/space/api/agent/v1/mcp`,
FirstPartyMcpProvider=class{ ... }
```

ardot 的 transport 解析（同文件 `abs=86873707` 附近）：

```js
function resolveTransport(e,n,i){let a=zl[n],o=devOverrideUrl(e.getDevOverride(a));
  ...
  if(n===`ardot`){
    if(!e.isProductFeatureOn(r.da.EnableArdot))return notApplicable();
    let n=e.getBuiltinArdotMcpUrl?.()??`https://ardot.tencent.com/mcp`,
        i=D.Nt(e.getDevOverride(a),n);
    return i.url!==n&&e.warn(`[FirstPartyMcpProvider] ardot dev \u8986\u76D6\u751F\u6548：url=${i.url}…`),
      ok$1({kind:`http`,url:i.url??n,headers:i.headers})}
  if(!e.isSignedIn())return skip({id:i,reason:`credential-missing`,detail:`未登录`});
  ...
}
```

item 装配（`abs=86869169` 区段）：

```js
let c=n===`ardot`&&D.Pt(e.getDevOverride(i))!==void 0;
return{kind:`item`,item:{id:o,displayName:i,origin:a,transport:s.value,
  auth:{mode:`static`},                                  // ← 注意：static，不是 oauth
  dynamicHeaders:async i=>({...c?{}:await r(n,i),...resolveSessionHeaders(e,n,i)}),
  enabled:!0,deferLoading:Gl[n],...Kl[n]?{visibility:Kl[n]}:{}}}
```

> **`auth:{mode:"static"}`** 是关键：ardot **不走** WorkBuddy 的 `McpOAuthService`（`RemoteMcpClientController.requireOAuth()` 那条路）。它走 `dynamicHeaders` 回调 + `managedHeaders`。

`deferLoading / visibility` 配置（`abs=86873231` 附近）：

```js
var Gl={ardot:!1,netdrive:!0,"agent-mail":!0,"genie-baas":!0,"space-library":!0},   // deferLoading
    Kl={ardot:{welcomeModes:[`design`],sessionScopeExempt:!0}, ...};
```

→ **ardot 只在对局 `welcomeMode==="design"` 时可见**（`isMcpItemVisibleInSession`，`server.js` `rel=783721`）：

```js
function isMcpItemVisibleInSession(e,n){
  if(e.origin.kind===`connector`&&iu.has(e.origin.connectorId))return!1;
  let r=e.visibility;
  return r?.requiresProjectId&&!n.projectId
      || r?.welcomeModes&&r.welcomeModes.length>0&&(!n.welcomeMode||!r.welcomeModes.includes(n.welcomeMode))
      ?!1: ...}
```

### 2.2 URL 与 transport kind：**streamable HTTP，带 SSE 回退**

`/main/server-side-access-token.js`，`var a=`https://ardot.tencent.com/mcp``，`abs=86000461`、`abs=86000619`：

```js
var i=`ardot.tencent.com`;
function isArdotOverrideHostAllowed(e){try{let{hostname:t}=new URL(e);
  return t===i||t.endsWith(`.${i}`)}catch{return!1}}
function setArdotEmbedUrlProvider(e){}                       // ← 空函数，死代码
var a=`https://ardot.tencent.com/mcp`;
function resolveBuiltinArdotMcpUrl(n){
  return t.w(n)?e.En(n.endpoint,`-ardot`,`/mcp`)??`https://ardot.tencent.com/mcp`:a}
function buildBuiltinArdotMcpConfig(e=a){return{type:`http`,url:e,disabled:!1,
  managedBy:`workbuddy-builtin`,_workbuddyManagedAuth:`server-side`}}
```

**`type:"http"` 对应的是 MCP Streamable HTTP**，不是 SSE。真正的协商在 `SdkMcpRemoteTransport`（`/main/server.js` `rel=737878`，`abs=86842641`）：

```js
var SdkMcpRemoteTransport=class{ ... async open(e){
  let r=new URL(e.url), i=resolveTimeouts(e), a={sequence:0},
      o=createAuthorizingFetch(e,a,this.deps), s=negotiationKey(e.id,r),
      c=e.transportKind===`sse`?[`sse`]
        :this.negotiated.get(s)===`sse`?[`sse`,`streamable`]
        :[`streamable`,`sse`];
  this.deps.info?.(`[MCP-Connect] begin id=${e.id} target=${r.origin}${r.pathname} order=${c.join(`>`)} ...`);
  for(let l=0;l<c.length;l+=1){ ...
    try{let c=await this.connect(u,r,o,i.connectMs);
      return e.transportKind===`http`&&this.negotiated.set(s,u), ...
      new SdkRemoteSession(c,e,a,i,this.deps)}
    catch(r){let i=httpStatusOf(r),o=l+1<c.length&&isTransportUnsupported(i); ...
      this.deps.warn(`[SdkMcpRemoteTransport] ${e.id} ${u} 不可用（${i}），回退另一条传输`)}}
  throw Error(`[SdkMcpRemoteTransport] ${e.id} 传输协商未产出结果`)}
async connect(e,n,r,i){
  let a=new Client({name:this.deps.clientName,version:this.deps.clientVersion},{capabilities:{}}),
      o=e===`streamable`?new StreamableHTTPClientTransport(n,{fetch:r})
                        :new SSEClientTransport(n,{fetch:r});
  return await a.connect(o,{timeout:i}),a}};
function isTransportUnsupported(e){return e!==void 0&&e>=400&&e<500&&e!==401&&e!==403}
```

**结论**：
- 首选 `StreamableHTTPClientTransport`（MCP SDK，`@modelcontextprotocol/sdk`）
- 只有拿到 **4xx（非 401/403）** 才判定"该 transport 不支持"，回退 `SSEClientTransport`
- 401/403 **不**回退 —— 那是鉴权问题，交给 refresh 逻辑
- clientName 常量：`var hl=`WorkBuddy Connector``（`server.js` rel≈741000）
- 默认超时：`fl=6e4`（connect 60s）、`pl=6e4`（request 60s）

### 2.3 token 注入：`Authorization: Bearer`，通过 **authorizing fetch**

不是 SDK 的 `authProvider`，而是自定义 `fetch` 包装（`server.js` `abs=86846826`，`rel=742063`）：

```js
function createAuthorizingFetch(e,n,r){
  let i=h.An({id:e.id,phase:`mcp`},fetch);
  return async(a,o)=>{
    let s=new Headers(o?.headers);
    for(let[n,r]of Object.entries(await e.authorize()))s.set(n,r);   // ← 每次请求动态注入
    let c=r.getDispatcher?.(toUrl(a,e.url)),
        l=c===void 0?{...o,headers:s}:{...o,headers:s,dispatcher:c},
        u=n.sequence+=1, d=await i(a,l);
    return(d.status===401||d.status===403)&&(n.at=u,n.statusCode=d.status,
      n.headers=headersToRecord(d.headers)),d}}
```

`authorize()` 落到 `RemoteMcpClientController.authorizationHeaders`（`server.js` `abs=86852056`，`rel=747293`）：

```js
async authorizationHeaders(e){return this.withDefaultUserAgent({
  ...this.remoteTransport().headers,
  ...await this.configuration.dynamicHeaders?.(e)??{},
  ...await this.authHeaders()})}                       // authHeaders()= {} （因为 mode!==oauth）
```

`dynamicHeaders` = `FirstPartyMcpProvider.resolveAuthHeaders`（`server.js` `rel≈765`）：

```js
async resolveAuthHeaders(e,n){
  this.syncIdentity(),
  n.afterUnauthorized&&await this.refreshManagedHeaders(e);     // ← 401 后才刷
  let r={};
  if(e!==`ardot`)try{r=this.deps.getAuthHeaders(e)}catch{}      // ← ardot 跳过通用 auth header
  return{...r,...this.managedHeaders.get(e)}                    // ← ardot 只吃 managedHeaders
}
```

**所以 token 注入链是**：
`managedHeaders.get("ardot")` → `dynamicHeaders()` → `createAuthorizingFetch` 逐请求 `Headers.set()` → 发到 `https://ardot.tencent.com/mcp`。

> **没有 `WWW-Authenticate` 驱动的标准 MCP OAuth**。401 触发的是 `refreshManagedHeaders("ardot")` → `authRefreshers.get("ardot")` → `ArdotManager.refreshAccessToken()`（见 §3）。

### 2.4 `https://ardot.tencent.com/mcp` 是这一条路；另一条是「连 daemon 本地 MCP」——不适用

需要明确的边界：`WorkbuddyMcpServer`（`serverName="connector-proxy"`，`server.js` `abs=86875820`）是 **WorkBuddy 自己的聚合 MCP server**（把内置工具经 `mcp__connector-proxy__*` 暴露给 CLI worker）。**ardot 不走它**，ardot 是独立 first-party server，`serverId="ardot"`。

### 2.5 dev 覆盖（本地调试后门）

`resolveArdotMcpConfig`（`server-side-access-token.js` `abs=86001730`）支持 mcp.json 里写 `dev:true` 覆盖：

```js
function isValidArdotDevMcpServer(e){
  if(!e||typeof e!=`object`)return!1; let t=e;
  if(t.dev!==!0)return!1;
  let n=typeof t.url==`string`?t.url.trim():``;
  return n.length===0||!isValidHttpUrl(n)?!1:isArdotOverrideHostAllowed(n)}   // 必须 *.ardot.tencent.com

function resolveArdotStaticAuthToken(e){                    // 允许在 headers 里硬编码 Bearer
  if(!isValidArdotDevMcpServer(e))return;
  let{headers:t}=e; if(!t||typeof t!=`object`)return;
  let n=Object.entries(t).find(([e,t])=>e.toLowerCase()===`authorization`&&typeof t==`string`)?.[1],
      r=typeof n==`string`?n.replace(/^\s*Bearer\b\s*/i,``).trim():``;
  return r.length>0?r:void 0}

function buildArdotDevOverrideMcpConfig(e){let t=resolveArdotStaticAuthToken(e)!==void 0;
  return{type:`http`,url:e.url.trim(),...e.headers?...,
    ...(t?{}:{_workbuddyManagedAuth:`server-side`})}}       // 有静态 token 就不接管鉴权
```

这条路径在 `app-server-mcp-apps-host.js` 里被显式识别为「静态覆盖」并短路（`abs=75069550` 附近）：

```js
let resolveArdotStaticAuthToken$1=()=>r.Pt(e.mcpManager.getUserOverride(`ardot`)),h;
try{h=a({mcpManager:e.mcpManager,...,accessTokenProvider:{getToken:async()=>{
  let e=resolveArdotStaticAuthToken$1();
  return e?(s.info(`[McpAppsHost] Ardot canvas token from mcp.json static override`),{accessToken:e})
          :r.Rt(e=>fetchArdotAccessToken(m,void 0,e),$,`ardot.getToken`)}},
  ...
```

> **实测核对**：`C:\Users\bx233\.workbuddy\mcp.json` 里 **只有 `rea`** 一个 server，**没有 ardot 条目**
> → 证明生产路径确实是内存注入，不落盘。

### 2.6 环境 / 域名

`/main/dev-env-override.js`，`abs=78872520`（你给的 78876613 附近）：

```js
DEV_ENV_ENDPOINTS={prod:`https://www.workbuddy.cn`,staging:`https://staging.workbuddy.cn`},
devEnvStagingEndpointResolver={resolve:()=>void 0},
ARDOT_DEV_ENV_ENDPOINTS={prod:`https://ardot.tencent.com`,staging:`https://test.ardot.tencent.com`},
devEnvOverrideForceEnabled=!1,DEV_ENV_CACHE_TTL_MS=3e4,
```

域名白名单（`/main/origins.js`，`abs=85784682`，你给的 85784744 在此文件内 rel=2599）：

```js
var c=/^\/file\/[^/]+\/?$/i;
function isArdotHostname(e){let t=e.toLowerCase();
  return t===`ardot.tencent.com`||t.endsWith(`.ardot.tencent.com`)?!0:
    /^[a-z0-9-]+-ardot\.copilot(-staging)?\.qq\.com$/.test(t)}
function isArdotWebOriginUrl(e){ ... isArdotHostname(t.hostname) }
function isArdotDesignFileUrl(e){ ...
  return t.protocol!==`http:`&&t.protocol!==`https:`||!isArdotHostname(t.hostname)?!1:c.test(t.pathname)}
```

---

## 3. 鉴权：`ArdotManager` + connector-OAuth 影子账号

### 3.1 类定义

`/main/server.js` `abs=88686181`（`rel=2581418`）。这是整份报告里最核心的一段：

```js
ArdotManager=class{constructor(e){this.deps=e,this.readyUntil=0}
async refreshAccessToken(){
  if(this.readyUntil=0,await this.isStaticAuthOverride())return{};
  let{accessToken:e}=await S.r(await this.deps.getOauthApiDeps(),void 0,void 0,{forceRefresh:!0});
  return{Authorization:`Bearer ${e}`}}

async ensureReadyForDesignSession(){
  let e=await this.checkAuthorization();
  if(e===`connected`)return e;
  let n=await this.authorizeAndEnsureReady();
  return n.status===`authorized`?`connected`:(this.deps.logger?.info(
    `[ArdotManager] design session authorization failed`,{checkStatus:e,error:n.error||...}),e)}

async checkAuthorization(e){ ... let r=await this.deps.getMcpStatus(S.l); ...
  let i=this.runAuthorizationCheck(r,n).finally(...); this.inflightAuthorization=i; return i}

async runAuthorizationCheck(e,n){
  if(await this.isStaticAuthOverride())return this.resolveStaticAuthStatus(e,n);
  let r=await this.deps.getOauthApiDeps(),
      i=await D.zt(`GET`,`/v2/as/connector/oauth/${S.c}/status`,r),
      o=i?.code===0?i?.data?.status:`not_connected`;
  this.deps.logger?.info(`[ArdotManager] status response`,{code:i?.code,httpStatus:i?._debug?.httpStatus,
    requestId:i?.requestId??i?._debug?.requestId,status:o});
  if(o!==`connected`)return this.readyUntil=0,o===`expired`?`expired`:`not_connected`;
  try{return await this.syncAccessTokenAndEnsureReady(r),this.readyUntil=n+S.s,`connected`}
  catch(e){return this.readyUntil=0,this.deps.logger?.info(`[ArdotManager] connected status sync failed`,
    {error:a.vi(e)}),`expired`}}

authorizeAndEnsureReady(){ ... }                     // inflight 去重
async runAuthorize(){ ... let n=await this.acquireAccessToken(e);
  return await this.applyBearerAndEnsureConnected(n),
    this.readyUntil=(this.deps.now?.()??Date.now())+S.s,{status:`authorized`} ...}

async acquireAccessToken(e){let n=await this.connectShadowAccount(e);
  try{return await this.fetchAccessToken(e,n)}
  catch(r){if(!isAlreadyConnected(n))throw r;
    this.deps.logger?.info(`[ArdotManager] already connected but token unavailable, rebinding`,
      {error:a.vi(r)}),await this.revokeShadowAccount(e);
    let i=await this.connectShadowAccount(e);return this.fetchAccessToken(e,i)}}

async connectShadowAccount(e){let n=await this.postConnect(e,`v2`);
  return n?.code===0||isAlreadyConnected(n)?n:this.postConnect(e,`console`)}

async postConnect(e,n){
  let r=await D.zt(`POST`,`/${n}/as/connector/oauth/${S.c}/connect`,e,
    {code:`shadow_account_grant`},void 0,void 0,{redirect:`manual`});
  return this.deps.logger?.info(`[ArdotManager] connect response`,{route:n,code:r?.code,
    httpStatus:r?._debug?.httpStatus,requestId:r?.requestId??r?._debug?.requestId,
    location:r?._debug?.responseLocation}),r}

async revokeShadowAccount(e){try{let n=await D.zt(`POST`,`/v2/as/connector/oauth/${S.c}/revoke`,e); ...}}

fetchAccessToken(e,n){let r=n?.code===0?void 0:n?.msg||`Ardot internal authorization failed`;
  return S.r(e,r).then(({accessToken:e})=>(this.deps.logger?.info(`[ArdotManager] access token ready`),e))}

async syncAccessTokenAndEnsureReady(e,n){let{accessToken:r}=await S.r(e,n);
  this.deps.logger?.info(`[ArdotManager] access token ready`),await this.applyBearerAndEnsureConnected(r)}

async applyBearerAndEnsureConnected(e){
  await this.deps.mcpManager.setManagedHeaders({Authorization:`Bearer ${e}`});
  let n=await this.deps.mcpManager.connect(S.l);
  if(!n.success)throw Error(n.error||`Failed to connect Ardot MCP after authorization`)}
};
```

### 3.2 `fetchArdotAccessToken` —— 真正的 token 端点

`/main/app-server-mcp-apps-host.js`，`abs=74992546`：

```js
async function fetchArdotAccessToken(e,t,n,i){
  let a=await r.t(f,e,{...i,signal:n});
  a?.code!==0&&(a=await r.t(f,e,{...i,signal:n,route:`console`}));      // ← console 路由兜底
  let o=a?.data?.access_token??a?.data?.token;
  if(a?.code!==0||typeof o!=`string`||o.length===0)
    throw Error(a?.msg||t||`Ardot access token is unavailable`);
  let s=a?.data?.expire_at;
  return{accessToken:o,...typeof s==`number`&&Number.isFinite(s)?{expireAt:s*1e3}:{}}}   // ← 秒→毫秒
```

`r.t` 是导出的 `connectorOauthAccessToken` RPC，落到：

```js
// server.js 附近（connector-oauth RPC 注册）
n.Ni(e,`connectorOauthAccessToken`,async e=>{let n=await t.resolveOauthName(e),r=await t.getApiDeps();
  return callConnectorOauthApi(`GET`,`/v2/as/connector/oauth/${n}/accesstoken`,r,void 0,void 0,
    maybeLegacyEndpoint(r,n))}),
```

注意 `GET` + `forceRefresh` 走 query：

```js
connector/oauth/${e}/accesstoken${n?.forceRefresh?`?forc...
```

### 3.3 完整鉴权时序（据代码还原）

```
design 会话开始
  └─ sessionMcpConfigCoordinator.setEnsureDesignMcpReady(...)           ← server.js rel=2622319
       └─ ArdotManager.ensureReadyForDesignSession()
            ├─ checkAuthorization()
            │    ├─ getMcpStatus("ardot")     ← 本地 controller 状态
            │    ├─ GET  /v2/as/connector/oauth/{S.c}/status
            │    └─ status==="connected" ? syncAccessTokenAndEnsureReady()  (readyUntil = now + S.s)
            │
            ├─ (not connected) authorizeAndEnsureReady() → runAuthorize()
            │    ├─ acquireAccessToken()
            │    │    ├─ POST /v2/as/connector/oauth/{S.c}/connect   body {code:"shadow_account_grant"}
            │    │    │      redirect:"manual"   →  若失败再试 /console/as/connector/oauth/{S.c}/connect
            │    │    ├─ GET  /v2/as/connector/oauth/{S.c}/accesstoken[?forceRefresh=1]
            │    │    │      → { access_token | token, expire_at }
            │    │    └─ 若 "already connected 但 token 拿不到"：
            │    │         POST /v2/as/connector/oauth/{S.c}/revoke  → 重新 connect → 重新取 token
            │    └─ applyBearerAndEnsureConnected(token)
            │         ├─ firstPartyMcpProvider.setManagedHeaders("ardot", {Authorization:`Bearer ${token}`})
            │         └─ mcpManager.connect("custom-mcp:ardot")   →  streamable HTTP → https://ardot.tencent.com/mcp
            └─ readyUntil = now + S.s

运行中 401/403：
  createAuthorizingFetch 打标 → dynamicHeaders({afterUnauthorized:true})
    → refreshManagedHeaders("ardot") → authRefreshers.get("ardot")()
      → ArdotManager.refreshAccessToken()  → { Authorization: `Bearer ${newToken}` }
```

**关键设计点：**
- **token 不是存本地**，存的只是「connector 授权状态」。每次需要时向 WorkBuddy 后端 `GET .../accesstoken` 换。本地只有内存态 `managedHeaders` 和 `readyUntil` 有效期。
- **`S.s` = 本地 ready 缓存窗口**（秒或毫秒级常量，定义在 `server.js` 同模块作用域，被压缩为 `S`）。**未找到其字面值** —— 该对象在 asar 中未以可读字面量出现（推测在 `server-side-access-token.js` 的导出映射中，但导出名 `"s"` 解析到的是 zod schema `Zi`，属压缩后符号复用）。此处**明确标注为未确认**。
- **`S.c` = connector oauth name（推测为 `"ardot"`）**。同理未在 asar 中找到可读字面量；但 `zl={ardot:"ardot",...}` 与全站 `connector/oauth/${name}` 命名一致，且 `.workbuddy\logs\daemon.log` 中确实出现 `[ArdotManager] status response {"code":0,"httpStatus":200,...,"status":"connected"}`，证明该端点在生产环境返回 200 且已 connected。

### 3.4 OAuth 细节：**没有 PKCE，没有 client_id，没有 scope**

- **PKCE**：`code_challenge` 在 asar 中**只出现在 `@modelcontextprotocol/sdk/dist/cjs/client/auth.js`**（`abs=14844332` / `14844893`），即 SDK 自带的标准 MCP OAuth 实现：

```js
// node_modules/@modelcontextprotocol/sdk/dist/cjs/client/auth.js
    // Generate PKCE challenge
    const challenge = await (0, pkce_challenge_1.default)();
    const codeVerifier = challenge.code_verifier;
    const codeChallenge = challenge.code_challenge;
    authorizationUrl.searchParams.set('response_type', AUTHORIZATION_CODE_RESPONSE_TYPE);
    authorizationUrl.searchParams.set('client_id', clientInformation.client_id);
    authorizationUrl.searchParams.set('code_challenge', codeChallenge);
```
  **但** ardot 的 `auth.mode` 是 `"static"`，`RemoteMcpClientController.requireOAuth()` 对 ardot **不会被调用**（`authHeaders()` 直接返回 `{}`，因为 `mode!=="oauth"`）。
  → **Ardot 集成未使用 MCP 标准 OAuth，因此未使用 PKCE**。

- **`mcp:use` scope**：在 asar 全局搜索 `mcp:use` / `mcp%3Ause` → **0 命中**。未找到证据。

- **client_id**：Ardot 路径下无 `client_id`。`client_id` 全局只出现在 SDK 的 `auth-extensions.js` 文档注释与 OAuth 实现里，与 ardot 无关。
  → connector-OAuth 的 client 身份由 **WorkBuddy 后端**持有（用户已登录 WorkBuddy，`createConnectorOauthApiDeps` 带上 WorkBuddy 会话凭据），客户端只看到一个 `access_token`。这是**服务端代理式（server-side / shadow-account）OAuth**，不是客户端 OAuth。`_workbuddyManagedAuth:"server-side"` 这个字段名字本身就是自述。

- **`shadow_account_grant`**：`postConnect` 的额外参数 `{code:"shadow_account_grant"}` —— 语义是「为当前 WorkBuddy 用户影子创建/绑定一个 Ardot 账号授权」，`redirect:"manual"` 表示**不跟随重定向**，直接从响应里读授权结果。

- **刷新**：两条
  1. **被动**：401/403 → `afterUnauthorized` → `refreshManagedHeaders` → `refreshAccessToken()`（`forceRefresh:!0`）
  2. **主动**：`readyUntil` 到期（`S.s`）→ `checkAuthorization` 重新走 status + accesstoken 同步

- **`connector/oauth/{name}/{revoke|revokeall|oauthuser|status|connect|accesstoken|start|repos|branches|file|temp-login-url}`** 是 WorkBuddy 通用 connector 框架（同一套前缀被 tdocs-app / lexiang-ol / agentmail / git 等复用），ardot 只是其中一个 connector 名。

---

## 4. 右侧面板 / 画布：MCP Apps（双层 iframe + postMessage JSON-RPC）

### 4.1 ardot-canvas 是什么

`ardot_canvas` / `ardot-canvas` 是 **artifact 类型标识**，不是 iframe 名。

`/renderer/assets/ardot-canvas-yXuE0fe5.js`（5067 B，unpacked），`abs=2968594` 是 asar header 里的文件名条目：

```js
// 常量区（文件末尾）
t=`ardot/canvas`, n=`ardot://canvas/`, r=`mcp-app://artifact/ardot/`,
i=new Set([`ardot/open_design`,`ardot/create_design`]),
a=`ardot-open-design-canvas`, o=`design-new-canvas`,
s=/Created Ardot design file:\s*(.+?),\s*fileId\s+(\d+),\s*url\s+(https?:\/\/[^\s"'\\]+)/i,
c=/\bOpening Ardot editor for file\s+(\d+)\b/i,
l=/\bOpen Ardot design file:\s*(https?:\/\/[^\s"'\\]+|\d+)/i,
u=`https://ardot.tencent.com/file/`,                       // ← 你给的 88833633/88835837 同款拼接
d=new Set([`ardot.tencent.com`,`test.ardot.tencent.com`]);
```

```js
function isVirtualMcpAppArtifactUri(e){return e.startsWith(`ardot://canvas/`)||e.startsWith(`mcp-app://`)}
function extractArdotCanvasFileId(e){ ... `ardot://canvas/` → slice(15) ... }
function buildArdotWebFileUrl(e){return`${u}${encodeURIComponent(e)}?from=workbuddy`}   // ← ?from=workbuddy
function isArdotEmbedUrl(e){ ... t.protocol!==`https:`||!d.has(t.hostname)||t.pathname!==`/embed`)?!1;
  let n=t.searchParams.get(`file_id`); return!!n&&/^\d+$/.test(n)}
```

另 `server.js` 里的 artifact 归类（`abs=81816719` 在 `/main/log-acl-guard.js` rel=1037995）：

```js
function mapType(e){let t=(e.type||``).toLowerCase();
  if(t===`file-changes`||t===`tasks`)return null;
  if(t===`overview`||t===`plan`)return`document`;
  if(t===`ardot_canvas`||t===`mcp_app`||t===`app`)return`preview`;   // ← 82016719 处的判定
  ...}
```

`/main/server.js` `abs=86491043`（`rel=386280`）另一处：

```js
function displayTypeByKind(e){let n=e.toLowerCase();
  if(n===`overview`||n===`plan`)return`markdown`;
  if(n===`ardot_canvas`||n===`mcp_app`||n===`app`)return`html`}
```

→ **`ardot_canvas` 是一种 artifact kind，渲染成 `preview`/`html`，最终由 MCP App 面板承载。**

### 4.2 右侧面板注册：MCP Apps Host（host-side API 确实存在）

**这回答你的「有没有 host-side panel/webview 注册 API」—— 有，就是 MCP Apps。**

IPC/RPC 通道表，`/main/app-server-mcp-apps-host.js` `abs=74997581`（你给的 `abs=75069949` 前方）：

```js
var j={listApps:`mcpApps:listApps`,
  ensureToolDiscovered:`mcpApps:ensureToolDiscovered`,
  openHostApp:`mcpApps:openHostApp`,
  closeHostApp:`mcpApps:closeHostApp`,
  refreshToolResult:`mcpApps:refreshToolResult`,
  awaitBootstrapToolResult:`mcpApps:awaitBootstrapToolResult`,
  callTool:`mcpApps:callTool`,
  readResource:`mcpApps:readResource`,
  getToken:`mcpApps:getToken`,                       // ← 与 `ardot.getToken` 标签同名不同物
  appsChanged:`mcpApps:appsChanged`,
  notifyToolCall:`mcpApps:notifyToolCall`,
  notifyToolCallUpdate:`mcpApps:notifyToolCallUpdate`,
  openHostRequested:`mcpApps:openHostRequested`,
  recordModelContextUpdate:`mcpApps:recordModelContextUpdate`,
  getModelContextUpdate:`mcpApps:getModelContextUpdate`,
  getViewResource:`mcpApps:getViewResource`,
  listTools:`mcpApps:listTools`,
  listResources:`mcpApps:listResources`,
  listResourceTemplates:`mcpApps:listResourceTemplates`,
  listPrompts:`mcpApps:listPrompts`};
```

这些是 **daemon RPC**（`registry.handle`），renderer 通过 preload bridge 调用。
运行时日志 `~/.workbuddy/logs/mcp-apps-diag.log` 直接印证 `rpc.openHostApp` / `rpc.getToken` 被真实调用。

### 4.3 表面（surface）选择：`launchSurface: "panel"` 决定进右侧面板

`/main/app-server-mcp-apps-host.js` `abs=75059791`（`rel=71399`）：

```js
function isSurface(e){return e===`inline`||e===`panel`}
function readWorkBuddyUiMeta(e){
  if(!isRecord$1(e))return; let t=e[re]; if(!isRecord$1(t))return;
  let n=t[ie]; if(isRecord$1(n))return{...isSurface(n.launchSurface)?{launchSurface:n.launchSurface}:{}}}
function resolveMcpAppSurface(e){
  let t=readWorkBuddyUiMeta(e.requestMeta)?.launchSurface,
      n=readWorkBuddyUiMeta(e.toolMeta)?.launchSurface;
  return t??n??e.hostDefault??`inline`}
```

`inline` 的 artifact 不进 artifact 列表（`log-acl-guard.js` rel=1038582）：

```js
if(r.type===`mcp_app`&&a&&typeof a==`object`&&a.launchSurface===`inline`)return null;
```

**运行时实证**（`~/.workbuddy/logs/mcp-apps-diag.log`）：

```
openHostApp.appResolved appId=ardot/create_design prefixedToolName=ardot_create_design
  resourceUri=ui://ardot-remote/editor.html serverId=ardot
  toolMeta={"hasMeta":true,"metaKeys":["ui","ui/resourceUri","workbuddy","serverName","defer_loading"],
  "hasUi":true,"uiKeys":["resourceUri"],"resourceUri":"ui://ardot-remote/editor.html",
  "workbuddyKeys":["ui"],"workbuddyUi":{"launchSurface":"panel"}}
```

→ **`launchSurface` 来自 MCP server 返回的 `tool._meta.workbuddy.ui.launchSurface`**，值 `"panel"`。
**面板是「Ardot MCP server 在工具元数据里声明的」，不是 WorkBuddy 硬编码的。**

### 4.4 面板怎么把 Ardot 编辑器跑起来：**不是**直接 iframe `ardot.tencent.com`

Ardot 编辑器 HTML 是 **MCP App Resource**，由 server 通过 `resources/read` 返回，host 落到本地 HTTP server。

实例构造（`/main/app-server-mcp-apps-host.js`，`buildInstance`）：

```js
buildInstance(e,n,r,i,a){let o=this.port();
  return{appInstanceId:e,appId:n.mcpAppId,serverId:n.serverId,toolName:n.toolName,
    prefixedToolName:n.prefixedToolName,sessionId:i.sessionId,projectId:i.projectId,
    toolCallId:i.toolCallId,
    appUrl:`http://127.0.0.1:${o}/mcp-apps/${e}/app.html`,      // ← 本地 app.html
    sandboxUrl:`http://127.0.0.1:${o}${t.a}`,                   // ← /mcp-apps/sandbox-proxy.html
    preloadUrl:this.resolvePreloadUrl(),uiMetadata:r.uiMetadata,
    toolMeta:n.toolMeta,toolInput:a,toolResult:void 0,createdAt:Date.now(),html:r.html}}
```

CSP 由 host 生成（同文件 `buildCsp`）：

```js
function buildCsp(e){let t=...resourceDomains..., n=...connectDomains..., r=...frameDomains...
  return[`default-src 'self' 'unsafe-inline'`,
    `script-src 'self' 'unsafe-inline' 'unsafe-eval' blob: data: ${t}`.trim(), ...
    `connect-src 'self' ${n}`.trim(), ... r?`frame-src ${r}`:`frame-src 'none'`,
    `object-src 'none'`, ...]}
```

> **重要**：`app.html` 是本机 `127.0.0.1` 提供的，内容 = **Ardot server 返回的 HTML 快照**（`htmlLen=634551`，来自运行时日志）。
> 因此画布本体不是 `https://ardot.tencent.com` 的 iframe，而是「从 Ardot MCP server 取来的 HTML，在本地 origin 下执行」。
> 而 `https://ardot.tencent.com/file/<id>?from=workbuddy` 只是「在浏览器里打开该文件」的**外链**（`buildArdotWebFileUrl`），以及 `present_files` 工具明确禁止传入的 URL（见 §4.8）。

### 4.5 双层 iframe + Sandbox Proxy

`/main/origins.js`（16428 B）里内嵌了 sandbox proxy 的完整 HTML，`abs=85785561`（`rel=3416`）：

```js
var l=`/mcp-apps/sandbox-proxy.html`,
    u=new Set([`127.0.0.1`,`localhost`,`[::1]`]);
function isMcpAppsSandboxProxyUrl(e){try{let t=new URL(e);
  return t.protocol===`http:`&&u.has(t.hostname)&&t.pathname===l}catch{return!1}}
var d=`<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>MCP Apps Sandbox Proxy</title>
<style>html,body{margin:0;width:100%;height:100%;overflow:hidden}
#app{display:block;width:100%;height:100%;border:0}</style></head>
<body><script>
(function(){'use strict';
  var PROXY_READY='ui/notifications/sandbox-proxy-ready';
  var RESOURCE_READY='ui/notifications/sandbox-resource-ready';
  var SIZE_CHANGED='ui/notifications/size-changed';
  var hostWindow=window.parent;
  var innerFrame=null,innerWindow=null,appOwnsSize=false;
  ...
  function permissionAllow(permissions){
    // 默认下发 clipboard-write：Ardot 等 App 的「复制链接」走 navigator.clipboard.writeText；
    // 仅靠 App 声明时，未声明的资源在 Chromium 双层 iframe 下会被 Permissions-Policy 直接拦截。
    // fullscreen 同理，供 App 内放映/全屏播放调 Element.requestFullscreen()；
    // 外层由 agent-ui 的 buildSandboxAllowAttribute 下发，两处必须同步。
    var values=['clipboard-write','fullscreen'];
    if(permissions&&typeof permissions==='object'){
      if(permissions.camera)values.push('camera');
      if(permissions.microphone)values.push('microphone');
      if(permissions.geolocation)values.push('geolocation')}
    return values.join('; ')}
  function buildCspMeta(csp){ ... 'default-src \\'none\\'',
    "script-src 'unsafe-inline' 'unsafe-eval' blob: "+resource, ...}
  ...
```

**注意注释里直接点名 Ardot** —— 这是为 Ardot「复制链接」专门放的 `clipboard-write` 权限。

### 4.6 renderer 侧：`McpAppSandboxFrame`

`/renderer/assets/contexts-DgzzFjnf.js`（7,553,508 B, unpacked）：

```js
McpAppSandboxFrame=({runtime:e,appInstanceId:t,sandboxUrl:n,html:r,csp:i,permissions:a,className:o,onError:s})=>{...
  setup=async()=>{if(!f){
    mcpAppsDiag(`sandbox.setup.start`,{appInstanceId:t,sandboxUrl:n,htmlLen:r.length,...});
    try{
      assertCrossOriginSandboxUrl(n);
      let r=document.createElement(`iframe`);
      r.className=o??``,
      r.setAttribute(`sandbox`,`allow-scripts allow-same-origin allow-forms`),
      r.setAttribute(`allow`,buildSandboxAllowAttribute(a)),
      r.style.width=`100%`,r.style.height=`100%`,r.style.display=`block`,r.style.border=`0`,
      r.addEventListener(`load`,()=>{...rebindIfGuestWindowRecreated()}),s.appendChild(r),p=r,
      ...
      let c=r.contentWindow; if(!c)throw Error(`[McpAppSandboxFrame] iframe contentWindow 不可用`);
      m=c, installSandboxReadyHandler(),
      await e.connect(new E(c,c)),                  // ← MessagePort/PostMessage transport 连内层
      f||(r.src=withCspQuery(n,i), ...)             // ← src = sandbox-proxy.html?csp=...
    }catch(e){...}}}
  ...
```

sandbox ready 后把真 HTML 推给内层：

```js
e.bridge.sendSandboxResourceReady({html:n.html,
  sandbox:`allow-scripts allow-same-origin allow-forms`,
  csp:n.csp, permissions:n.permissions})
```

### 4.7 通信链路：`postMessage` JSON-RPC（**不是** MessageChannel，**不是** wb-guest-sdk）

三层：

```
[daemon: McpAppsGateway]
      ▲  mcpApps:* RPC（Electron IPC / daemon RPC）
      │
[renderer: McpAppsCoordinator / McpAppSandboxFrame]
      ▲  window.postMessage({jsonrpc:"2.0",...})     ← 标准 MCP Apps PostMessageTransport
      │
[iframe #1: sandbox-proxy.html]  (127.0.0.1，持有 CSP)
      ▲  window.postMessage
      │
[iframe #2: app.html]  ← Ardot 编辑器 HTML
```

关键：**Ardot 的 editor HTML 内部用 MCP Apps SDK 的 `PostMessageTransport`（`window.parent.postMessage`）**，proxy 负责中转 + 注入 CSP meta + 上报 size。

`/resources/mcp-app-preload.js`（10220 B）是**另一个 webview 场景**（不是 iframe 场景）的 preload，注释说明了 Electron `<webview>` 里 `window.parent===window` 的自环问题：

```js
// MCP Apps webview preload — runs inside the sandboxed `<webview>` that
// hosts an MCP App's HTML resource. Bridges the app's `window.postMessage`
// JSON-RPC traffic to the host renderer via Electron's
// `ipcRenderer.sendToHost` / `ipc-message` pair.
...
// In a normal browser iframe, `window.parent !== window`, so the MCP Apps
// SDK's `PostMessageTransport.send` (which calls
// `window.parent.postMessage(msg, "*")`) targets the parent window and
// never comes back locally. Inside an Electron `<webview>` there is no
// real parent: `window.parent === window`. A naïve preload that just
// listened on `message` would see every outbound SDK request come back
// through its own transport listener, ...
//   1. Register a CAPTURE-phase `message` listener...
//   2. For app-originated JSON-RPC payloads we forward to the host and
//      call `stopImmediatePropagation()`...
//   3. For host-originated payloads ... stashing them in a `WeakSet` ...

window.addEventListener('message', event => {
  const data = event && event.data;
  if (!data || typeof data !== 'object' || data.jsonrpc !== '2.0') return;
  ...
  // app -> host: forward to host, then prevent the SDK's own
  // bubble-phase transport listener ...
```

它暴露给 guest 的测试 API 里有：

```js
// `window.__mcpHostTestApi.getToken()` when host replies to the
// McpAppsBridge's `host/getToken` handler.
getToken() { return requestHost('host/getToken', {}); }
```

**所以 `getToken` 有两条通路**：
1. **MCP Apps App 侧**：`host/getToken` → renderer → `mcpApps:getToken` → daemon `McpAppsGateway.getToken(appInstanceId)`
2. **daemon 内部**：`accessTokenProvider.getToken()` → `fetchArdotAccessToken`（`ardot.getToken` 标签）

daemon 侧 handler（`apps-host.js`）：

```js
t.handle(j.getToken,async e=>{
  if(typeof e!=`string`||e.length===0)throw Error(`appInstanceId required`);
  return mcpAppsDiag(`rpc.getToken`,{appInstanceId:e}),n.getToken(e)}),
```

```js
async getToken(e){let t=this.instances.get(e);
  if(!t)throw Error(`Unknown MCP App instance: ${e}`);
  if(!this.deps.accessTokenProvider)throw Error(`MCP App access token provider is not configured`);
  return this.deps.accessTokenProvider.getToken({appId:t.appId,appInstanceId:e,sessionId:t.sessionId})}
```

→ **Ardot 编辑器 HTML 自己就能拿到 Ardot access token**（不给用户看，直接用于自己的 XHR/WebSocket）。
运行时日志显示 Ardot 面板打开时会连续调多次：

```
[2026-09-11T14:38:30.351Z] rpc.getToken appInstanceId=0279c590-...
[2026-09-11T14:38:30.525Z] rpc.getToken appInstanceId=0279c590-...
```

### 4.8 与 wb-guest-sdk 的关系：**不同机制**

`wb-guest-sdk/wb.js`（18729 B, unpacked）是 **extension iframe / webview** 的 SDK，用于 WorkBuddy 自己的 extension（`resources/extensions/*`）。注入方式（`/main/main-bootstrap.js`）：

```js
WB_GUEST_SDK_URL=`https://www.workbuddy.cn/wb.js`,
WB_GUEST_URL_FILTER={urls:[WB_GUEST_SDK_URL]};
function resolveWbGuestBundlePath(){
  let e=process.env.WB_GUEST_SDK_BUNDLE_PATH; if(e&&node_fs.existsSync(e))return e;
  if(require_workbuddy_packaged_runtime.t(electron.app)){
    let e=node_path.join(process.resourcesPath,`wb-guest-sdk`,`wb.js`);
    if(node_fs.existsSync(e))return e}
  ...}
function installWbGuestProtocol(e){let t=e.session,n=resolveWbGuestBundlePath();
  if(!n)return require_logger.c.warn("[wb-guest-protocol] wb.js bundle NOT found; ..."),()=>void 0;
  let r=`local-file://${encodeURI(n.replace(/\\/g,`/`))}`;
  return t.webRequest.onBeforeRequest(WB_GUEST_URL_FILTER,(e,t)=>{t({redirectURL:r})}),
  ()=>{t.webRequest.onBeforeRequest(WB_GUEST_URL_FILTER,null)}}
```

即：**把 `https://www.workbuddy.cn/wb.js` 请求重定向到本地文件**。

**Ardot 面板不使用 wb.js**。搜索 `wb-guest-sdk` 在 asar 内仅 1 处命中（上面这段）；Ardot 的 MCP App 走标准 MCP Apps PostMessage 协议 + `mcp-app-preload.js`。

> 你已读过 `wb.js` 是 postMessage/MessagePort 网桥 —— 那是 extension 体系；**Ardot 用的是 MCP Apps 体系**，两者协议层不同（MCP Apps 是 JSON-RPC 2.0 + `ui/*` 方法）。

### 4.9 右侧面板在 renderer 的落点

`/renderer/assets/detail-panel-wrapper-BZPa078h.js`（57,517 B）暴露了一整套「MCP App 面板」props：

```js
...onMcpAppBridgeMessage, registerMcpAppsPostMessage, onMcpAppWebviewLoadError,
   notifyMcpAppDisplayModeChanged,
   onGenerateAppFromArdotCanvas,                 // ← Ardot 专属：从画布生成 App
   onArdotMcpAppViewportChange,                  // ← Ardot 专属：视口变化
   mcpAppStandardPanelSlot, mcpAppStandardPanelUsesArtifactNavigation,
   ardotCanvasCollapseRequestSeq, ...
```

```js
detailMainOverlay: Tu?(0,U.jsx)(mn,{target:`ardot`,variant:`overlay`}): ...
```

`/renderer/assets/artifact-slot-panel-DlzKPlfh.js` 里也用到 `EnableArdot` feature flag：

```js
import{d as Ye,f as Xe,h as Ze,u as j}from"./ardot-canvas-yXuE0fe5.js";
...
var et=e((()=>{})),tt,nt=e((()=>{tt=`EnableArdot`})),rt=e((()=>{nt()}));
function createProductFeatureHook(e){...}
```

→ **`EnableArdot` 是产品特性开关**（`productFeatures.EnableArdot`），在 `resolveTransport` 里先检查。

---

## 5. 技能注入

### 5.1 打包在 asar 里 —— **不走服务端下发**

`/resources/plugins/workbuddy-builtin/builtin-plugins/ardot/` 全部 `unpacked=U`：

| 文件 | 大小 |
|---|---|
| `.codebuddy-plugin/plugin.json` | 406 |
| `skills/ardot-design-core/SKILL.md` | 16,642 |
| `skills/ardot-design-core/func-rule/component-instance/SKILL.md` | 12,812 |
| `skills/ardot-design-core/func-rule/design-variables/SKILL.md` | 7,036 |
| `skills/ardot-design-core/func-rule/shared-styles/SKILL.md` | 1,552 |
| `skills/ardot-design-core/references/ardot-schema.md` | 8,483 |
| `skills/ardot-design-core/rules/design-rules.md` | 24,028 |
| `skills/ardot-design-core/rules/effects-guide.md` | 7,126 |
| `skills/ardot-design-core/rules/style-guide.md` | 12,188 |
| `skills/ardot-design-core/tool-usage/apply-variables.md` | 2,273 |
| `skills/ardot-design-core/tool-usage/batch-edit.md` | 18,113 |
| `skills/ardot-design-core/workflows/ardot-workflow.md` | 8,897 |
| `skills/ardot-design-to-code/SKILL.md` | 2,654 |
| `skills/ardot-design-to-code/references/guidelines-code.md` | 8,508 |
| `skills/ardot-design-to-code/references/guidelines-tailwind.md` | 10,314 |
| `skills/ardot-design-to-code/workflows/design-to-code-workflow.md` | 21,262 |
| `skills/ardot-design-to-code/workflows/extract-style-guide-from-web.md` | 20,298 |
| `skills/ardot-poster/SKILL.md` | 2,801 |
| `skills/ardot-poster/references/guidelines-poster.md` | 21,027 |
| `skills/ardot-slides/SKILL.md` | 3,107 |
| `skills/ardot-slides/references/guidelines-slides.md` | 6,307 |
| `skills/ardot-slides/workflows/slides-workflow.md` | 15,374 |
| `skills/ardot-ui-design/SKILL.md` | 2,899 |
| `skills/ardot-ui-design/references/guidelines-landing-page.md` | 22,586 |
| `skills/ardot-ui-design/references/guidelines-mobile-app.md` | 6,478 |
| `skills/ardot-ui-design/references/guidelines-table.md` | 1,022 |
| `skills/ardot-ui-design/references/guidelines-web-app.md` | 4,760 |
| `skills/design-router/SKILL.md` | 6,770 |
| `skills/livestream-poster/**` | ~40 KB |

`plugin.json`：

```json
{
  "name": "ardot",
  "version": "5.8.3-wb.41334564.gdb0e9b51.h4da087921e4a",
  "description": "WorkBuddy Ardot design capabilities: shared design guidance, intent routing, UI, slides, posters, livestream posters, and design-to-code skills.",
  "author": { "name": "WorkBuddy" },
  "category": "设计工具",
  "keywords": ["workbuddy", "ardot", "design"],
  "skills": "./skills"
}
```

marketplace 注册（`resources/plugins/workbuddy-builtin/.codebuddy-plugin/marketplace.json`）：

```json
{"name":"ardot","description":"WorkBuddy Ardot design capabilities.",
 "source":"./builtin-plugins/ardot","category":"builtin-plugin",
 "version":"5.8.3-wb.41334564.gdb0e9b51.h4da087921e4a"}
```

**投递路径**（`/main/daemon-app-server-main.js`、`main-bootstrap.js`）：

```js
builtinSkillsDir:o.j(`plugins`,`workbuddy-builtin`,`skills`)
builtinSkillsDir:c(`plugins/workbuddy-builtin/skills`)
```

remark：注意这两个路径是 `plugins/workbuddy-builtin/**skills**`，**不是** `builtin-plugins/ardot/skills`。
`builtin-plugins/ardot` 是 **plugin marketplace** 体系（`marketplace.json` → `plugins[30]`），由 `PluginMcpProvider` / plugin loader 加载，`skills: "./skills"` 由 plugin manifest 声明。
→ **两套并行**：`builtin-plugins/*/skills`（plugin 内嵌 skill）+ `plugins/workbuddy-builtin/skills`（顶层 builtin skill 目录）。Ardot 走前者。

### 5.2 技能怎么规定必须用 ardot_* MCP 工具

**`ardot-design-core/SKILL.md`**（16642 B）正文开头：

```markdown
# Ardot Design Core

Foundational workflow and hard rules for completing design tasks on the Ardot canvas
via the **ardot MCP server**. **This skill is the shared base** — it is injected
together with one domain skill (UI design / slides / poster / design-to-code).
**All canvas manipulation MUST go through ardot MCP tools.**
```

frontmatter：

```yaml
---
name: ardot-design-core
description: "Foundational workflow and hard rules shared by ALL Ardot canvas design tasks
  (UI screens, slides, posters, livestream posters, design systems, design-to-code). ...
  It is injected automatically alongside a domain-specific Ardot skill
  (ardot-ui-design / ardot-slides / ardot-poster / livestream-poster / ardot-design-to-code). ..."
allowed-tools:
disable-model-invocation: true
user-invocable: false
version: "0.1.0"
---
```

硬规则（原文，含 emoji）：

```markdown
> ⛔ **HARD RULE — NEVER CALL MIORA CANVAS TOOLS.** This is a 100% Ardot design task.
  Never call any Miora canvas tools. NEVER call `miora_write_canvas` and
  `miora_open_canvas`, it's not allowed.

> ⛔ **HARD RULE — NO SUB-AGENTS.** Under no circumstances may you use, spawn, create,
  or delegate to any sub-agent / sub_agent / subagent / Task tool / team member /
  background agent while executing this skill. ...

> ⛔ **HARD RULE — OUTPUT LANGUAGE.** ...
```

**工具名写法**（重要）：技能里写的是**裸工具名**，不带 `ardot_` 也不带 `mcp__ardot__`：

```markdown
| `{SKILL_ROOT}/tool-usage/batch-edit.md` | `batch_edit` tool usage guide — load anytime `batch_edit` is used |
```

统计 `ardot-design-core/SKILL.md`：`create_design` × 11、`open_design` × 8、`batch_edit` × 7、`fetch_editor_state` × 3；**`mcp__` 出现 0 次**。

→ 技能层假定「裸名可用」。实际解析靠 §6 的别名展开。

### 5.3 每轮注入的 prompt 段落（**host 侧，不是 skill 文件**）

`/main/tar.js`（1,375,281 B）里有三个 `every_turn` section。

**① `<ardot_file_directive>`** —— `abs=90904689`（`rel=873270`）：

```js
var ArdotFileDirectiveSection=class{constructor(){this.stage=`every_turn`}
  shouldApply(e){return isCraftMode(e.codebuddyMeta)&&getArdotFileAction(e.codebuddyMeta)!==void 0}
  async render(e){if(!isCraftMode(e.codebuddyMeta))return;
    let t=getArdotFileAction(e.codebuddyMeta);
    if(t)return[`<ardot_file_directive action="${t}">`,renderInstruction(t),
      `</ardot_file_directive>`].join(`\n`)}}
function isCraftMode(e){let t=e.mode;return t===void 0||t===`craft`}
function renderInstruction(e){switch(e){
  case`create`:return`A new blank Ardot design file MUST be created for this task.
    Call create_design EXACTLY ONCE as your first canvas action, then NEVER call
    create_design again this turn. Do NOT re-run the Step 0 create/open decision tree,
    and do NOT call create_design a second time to "make sure" the file exists —
    once issued, treat the file as being created and proceed
    (use fetch_file_info if you need the file id).`;
  case`open`:return`The user referenced an existing Ardot design file.
    Call open_design EXACTLY ONCE with the provided file URL/ID, then proceed.
    Do NOT call create_design for this task, and do NOT re-run the Step 0 create/open decision tree.`;
  case`ambiguous`:return`The user used a "create/make" verb, but the request has no clear
    design intent (no design artifact like poster/page/slides, no design template scene,
    no reference image). Do NOT call create_design and do NOT create any blank design file yet.
    First, briefly ask the user to confirm what they want: are they asking you to create
    a new Ardot design file, or is this a non-design request (e.g. creating an issue,
    a document, running a task)? Only proceed to create_design after the user confirms
    they want a new design file.`;
  default:return``}}
```

`getArdotFileAction`（`abs=90168968`，`rel=137549`）：

```js
function getArdotFileAction(e){let t=e.ardotFileAction;
  return t===`create`||t===`open`||t===`ambiguous`?t:void 0}
```

`ardotFileAction` 来自客户端请求的 `codebuddy.ai` 元数据，在 `/main/netdrive-env-route.js` `abs=85198350`（`rel=934395`）被读取，并作为 OpenTelemetry span 属性：

```js
S=String(f.expertId??``), C=String(f.ardotFileAction??``),
w=String(f.conversationId??e); ...
span.startSpan(`daemon.prompt.receive`,{kind:o.p.SERVER,attributes:{...
  "workbuddy.conversation_request_id":m,...}})
```

→ 用户点了「新建/打开/含义不明」，renderer 传 `ardotFileAction`，host 转成 `<ardot_file_directive>` 塞进本轮 prompt。
这是**交互意图 → prompt 的硬注入通道**。

**② `<ardot_design_style>`** —— `abs=90901801`（`rel=870382`）：

```js
ArdotDesignStyleSection=class{constructor(){this.stage=`every_turn`}
  shouldApply(e){if(getDesignStyleType(e.codebuddyMeta)===void 0)return!1;
    let t=getDesignStyleUrl(e.codebuddyMeta),n=getDesignStyleCandidates(e.codebuddyMeta);
    return t!==void 0&&t!==``||n!==void 0}
  async render(e){let t=getDesignStyleType(e.codebuddyMeta); if(!t)return; ...}}
```

渲染出的实际文本（同文件 `renderSingle`）：

```js
function renderSingle(e,t,n){let r=extractFilename(n),i=[`type="${escapeAttr(e)}"`];
  return t&&i.push(`id="${escapeAttr(t)}"`),i.push(`url="${escapeAttr(n)}"`),
  [`<ardot_design_style ${i.join(` `)}>`,
   `[style-template] user selected template ${r}, use \`curl ${n}\` to fetch template contents
    as the base visual system and skip search_style_guide/build_style_guide.
    If the user message also states explicit style/visual constraints (palette, mood,
    material, typography, layout preferences, etc.), those win on conflict;
    the template only fills unspecified gaps. [/style-template]`,
   `</ardot_design_style>`].join(`\n`)}
```

**③ `<ardot_image_gen>`** —— `abs=90906406`（`rel=874987`）：

```js
var ArdotImageGenSection=class{constructor(){this.stage=`every_turn`}
  shouldApply(e){return getImageMode(e.codebuddyMeta)!==void 0}
  async render(e){let t=getImageMode(e.codebuddyMeta);
    if(t)return`<ardot_image_gen mode="${t}" />`}}
function getImageMode(e){let t=e.imageMode;return t===`ai`||t===`placeholder`?t:void 0}
```

**④ `<ardot-design-skill-requirement>`** —— `/main/daemon-app-server-main.js` `abs=76356391`（`rel=108472`）：

```js
var $e=`ardot-design-core`,
    et=[`<ardot-design-skill-requirement>`,
        `For design tasks, follow the Ardot design Skills injected into this conversation `,
        "(`ardot-design-core` plus the matching domain skill) and execute their workflow strictly.",
        `</ardot-design-skill-requirement>`].join(`\n`);
function ensureArdotDesignPromptContract(e,t){
  return t!==`design`||e===void 0||e.includes($e)?e:`${e.trimEnd()}\n\n${et}`}
```

调用点（同文件，systemPrompt 装配处）：

```js
let h=ensureArdotDesignPromptContract(d.systemPrompt,c.welcomeMode), g=e.resolveLanguage?.(), ...
```

→ **`welcomeMode==="design"` 时，host 往 system prompt 尾部追加一段契约**，保证模型一定读 skill。

### 5.4 skill 配对表（host 侧硬编码）

`/main/node.js` `abs=85648827`（`rel=277296`）：

```js
var Yn=[[`ardot-design-core`,`ardot-ui-design`],
        [`ardot-design-core`,`ardot-slides`],
        [`ardot-design-core`,`ardot-poster`],
        [`ardot-design-core`,`livestream-poster`],
        [`ardot-design-core`,`ardot-design-to-code`],
        [`miora-creative-core`,`miora-image-generation`],
        [`miora-creative-core`,`miora-video-generation`],
        [`miora-creative-core`,`miora-brand-design`]];
```

配套的「修复自动挂载」（`abs=85649396`，`rel=277865`）：

```js
function repairLegacyAutoAttachedDesignSkills(e){
  let t=/<manually_attached_skills>([\s\S]*?)<\/manually_attached_skills>/i.exec(e)?.[1];
  if(!t)return e;
  let n=new Set([...t.matchAll(/(?:^|\r?\n)name:\s*([^\s<]+)\s*(?=\r?\n|$)/gi)]
        .map(e=>e[1]?.toLowerCase()).filter(e=>!!e)),
      r=/<(?:ardot_image_gen|ardot_design_style|ardot_file_directive)\b/i.test(e);
  ...
```

→ host 会把 `<ardot_image_gen>` / `<ardot_design_style>` / `<ardot_file_directive>` 与「手动挂载的 skill 名」联动，清理用户 query 尾部的 `@skill:` 序列。

### 5.5 design welcomeMode 的 root agent

`/resources/plugins/workbuddy-builtin/welcomemode/design/`（你给的 2968594 附近亦属此行）：

```json
// .workbuddy-plugin/plugin.json
{"name":"welcomemode-design","category":"welcomeMode",
 "agents":["agents/design.md"],
 "workbuddy":{"dependencies":[{"type":"mcp","name":"netdrive"}]}}   // ← 注意只依赖 netdrive
```

```markdown
<!-- agents/design.md -->
---
name: design
description: WorkBuddy design welcomeMode root agent.
---
{% include "welcomemode-design/prompt.tpl" %}
```

`prompt.tpl`（25,938 B）关键片段（渲染后）：

```markdown
- The canvas, file formats, and underlying skills are **tools you use**, not who you are.
  Describe your work in design language ("I'll lay out the page", "I'll refine the visual style"),
  not by naming the tools.

... **follow the Ardot design Skills injected into this conversation**
(`ardot-design-core` plus the matching domain skill — UI / slides / poster / design-to-code,
or `design-router` when undecided).
```

> **注意**：`welcomemode-design` 的 `workbuddy.dependencies` **只写了 `netdrive`**，**没有 `ardot`**。
> Ardot MCP 的可见性不靠这个依赖，而是靠 `visibility.welcomeModes:["design"]` + `EnableArdot` feature flag（§2.1）。
> 这与 `marketplace.json` 里 `welcomemode-design` 的 `"dependencies":["mcp-miora","ardot"]` 不一致 —— 两处声明不同，**以运行时 `FirstPartyMcpProvider` 为准**。

### 5.6 `design-router`

`skills/design-router/SKILL.md`（6770 B）是**纯路由器**，负责在 Ardot（可编辑画布）和 Miora（生成像素）之间分流：

```markdown
| **Ardot** — canvas design | An **editable design file** (可编辑设计稿): live type, layers, layout
  the user can keep changing, re-exporting, or handing off | `ardot-design-core` |
| **Miora** — generative media | A **finished AI-generated image or video** (纯图片/视频):
  pixels, not layers — no further canvas editing | `miora-creative-core` |
...
- User wants to **keep editing / restructure / export to code** → Ardot.
- User asks for a **video or motion** → Miora, always. Ardot has no video.
- Neither is clear → ask, do not guess.
```

`user-invocable: true`（其余 ardot skill 均 `false`），`disable-model-invocation: true`。

---

## 6. 工具命名：`mcp__ardot__<tool>` 是真名，`ardot_<tool>` 是扁平别名

### 6.1 MCP 层：`mcp__<serverId>__<toolName>`

`/main/server.js` `abs=86937509`（`rel=832746`）：

```js
for(let o of n.listTools()){
  if(o.isApp!==!0||a.has(o.name))continue;
  let s=readMcpAppToolUiMetadata(o); if(!s?.resourceUri)continue;
  let c=`${i}/${o.name}`;
  e.set(c,{mcpAppId:c, mcpAppName:c, configId:r.id,
    runtimeSource:n.transport.kind===`stdio`?`session-stdio`:`remote`,
    serverId:i,
    prefixedToolName:`mcp__${i}__${o.name}`,        // ← 就在这里生成
    toolName:o.name, resourceUri:s.resourceUri, ...
```

同文件 `abs=86886966`（`rel=782203`）：

```js
if(u){a.servers[l]=u;
  for(let e of c.disabledTools??[]){let n=e.trim();n&&o.add(`mcp__${l}__${n}`)}
  ...}
```

**CLI worker 侧**（`app.asar.unpacked/cli/dist/codebuddy-lite-wb.mjs`）：

```js
...if("name"===A){let t=Reflect.get(E,A,R); if(h&&s?.has(t))return t;
  if(n){if(s?.has(t))return t; let i=c?.[t]?.server_name;
    if(i){let n=i+"_",s=t.startsWith(n)?t.substring(n.length):t;
      return`mcp__${i}__${s}`}}                              // ← 剥掉 server 前缀再加标准前缀
  return`mcp__${i}__${t}`}
```

```js
let S=`mcp__${n}__${s}`, E={};
return{content:await h.callTool(S,c,{__resultMetaSink:t=>{E=t},__skipLoopGuard:!0}), ...}
```

**运行时实证**（`~/.workbuddy/logs/mcp-apps-diag.log`）：

```
prefixedToolName=mcp__ardot__create_design
prefixedToolName=mcp__ardot__open_design
toolCallId=mcp__ardot__open_design_0_e98681fa
toolCallId=mcp__ardot__open_design_1
```

```
mcp__ardot__batch_edit
mcp__ardot__batch_read
mcp__ardot__capture_layout
mcp__ardot__capture_screenshot
mcp__ardot__create_design
mcp__ardot__fetch_editor_state
mcp__ardot__locate_available_space
mcp__ardot__open_design
```

→ **Ardot MCP server 返回的工具名是裸名**（`create_design`、`open_design`、`batch_edit`…），**不带 `ardot_` 前缀**。
→ **`ardot_` 是 host 侧在做「扁平别名」时产生的**，不是 server 的。

### 6.2 别名展开：`mcp__ardot__create_design` ⇄ `ardot_create_design`

`/main/server.js` `abs=86805893`（`rel=701130`）：

```js
function expandMcpAppToolNameAliases(e){
  let n=new Set([e]);
  if(e.startsWith(`mcp__connector-proxy__`)){let r=e.slice(22);r&&n.add(r)}
  if(e.startsWith(`mcp__`)){
    let r=e.slice(5), i=r.indexOf(`__`);
    i>0&&n.add(`${r.slice(0,i)}_${r.slice(i+2)}`)      // ← mcp__ardot__create_design → ardot_create_design
  }else{
    let r=e.indexOf(`_`);
    r>0&&n.add(`mcp__${e.slice(0,r)}__${e.slice(r+1)}`) // ← ardot_create_design → mcp__ardot__create_design
  }
  return[...n]}
```

用于 `findAppByToolName`：

```js
findAppByToolName(e){let n=new Set(expandMcpAppToolNameAliases(e));
  return this.listApps().find(e=>n.has(e.prefixedToolName))}
```

### 6.3 `ardot_` 前缀的 host 侧判定

`/main/app-server-mcp-apps-host.js` `abs=75014220`（`rel=25828`）：

```js
function isArdotHostAppRef(e){
  let t=e.appId??``, n=e.prefixedToolName??``;
  return t.startsWith(`ardot/`)||n.includes(`mcp__ardot__`)||n.startsWith(`ardot_`)}
```

→ **三种形态都认**：`ardot/<op>`（appId）、含 `mcp__ardot__`（标准）、以 `ardot_` 开头（扁平）。

### 6.4 `ardot_` 的用途：**UI 展示 / i18n / 日志**

`/main/server.js` `abs=87192253` 起，i18n 表用的就是 `ardot_<tool>`：

```js
"tool_executing.ardot_apply_variables":`正在应用设计变量`,
"tool_executing.ardot_batch_edit":`正在批量编辑设计`,
"tool_executing.ardot_batch_read":`正在批量读取设计`,
"tool_executing.ardot_capture_layout":`正在捕获布局`,
"tool_executing.ardot_capture_screenshot":`正在截取设计稿`,
"tool_executing.ardot_create_design":`正在创建设计文件`,
"tool_executing.ardot_export_nodes":`正在导出…`,
...
// 英文
"tool_executing.ardot_fetch_variables":`Fetching design variables`,
"tool_executing.ardot_open_design":`Opening design file`,
"tool_executing.ardot_save_tokens":`Saving design tokens`,
"tool_executing.ardot_scan_exportable_resources":`Scanning exportable resources`,
"tool_executing.ardot_upload_images":`Uploading images`,
```

出现过的 `ardot_*` i18n key（部分）：
`ardot_apply_variables`、`ardot_batch_edit`、`ardot_batch_read`、`ardot_capture_layout`、
`ardot_capture_screenshot`、`ardot_create_design`、`ardot_export_nodes`、`ardot_fetch_editor_state`、
`ardot_fetch_styles`、`ardot_fetch_variables`、`ardot_locate_available_space`、`ardot_open_design`、
`ardot_save_tokens`、`ardot_scan_exportable_resources`、`ardot_upload_images`

→ 这就是你搜到的 `ardot_design_style` / `ardot_image_gen` / `ardot_upload_images` / `ardot_batch_edit` 的来源：
**`ardot_design_style` / `ardot_image_gen` 是 prompt 标签（§5.3），`ardot_upload_images` / `ardot_batch_edit` 是 i18n key + 扁平别名。**

### 6.5 小结表

| 层 | 形态 | 来源 |
|---|---|---|
| MCP server 返回 | `create_design` | Ardot server `tools/list` |
| MCP 客户端内部 key | `mcp__ardot__create_design` | `server.js` `prefixedToolName` |
| 模型调用 | `mcp__ardot__create_design` | 同上（日志实证） |
| 扁平别名 | `ardot_create_design` | `expandMcpAppToolNameAliases` |
| UI/i18n/日志 | `ardot_create_design` | i18n `tool_executing.ardot_*` |
| Skill 文件里 | `create_design`（裸名） | `ardot-design-core/SKILL.md` |
| MCP App appId | `ardot/create_design` | server `tool._meta` |
| Resource URI | `ui://ardot-remote/editor.html` | server `tool._meta.ui.resourceUri` |

---

## 7. 未找到证据 / 未确认项（明确声明）

| 项 | 状态 |
|---|---|
| `S.c`（connector oauth name，推测 `"ardot"`）的字面值 | **未在 asar 中找到可读字面量**。依据命名一致性 + 运行时日志状态 200/connected 推断。 |
| `S.s`（ready 缓存窗口）的字面值 | **未找到**。被压缩为模块作用域 `S` 对象属性，asar 内无可读定义（`ssat.js` 的 `exports.s` 解析到 zod schema，属符号复用，非该常量）。 |
| `mcp:use` scope | **0 命中**，未找到证据。Ardot 路径不走 MCP 标准 OAuth，故预期不存在。 |
| 客户端 `client_id` / PKCE `code_verifier` | **未找到**（仅 SDK 自带实现中存在，未被 ardot 路径使用）。 |
| `S.c` 对应的 connector 注册记录 | `~/.workbuddy/connectors/` 下未 grep 到 ardot；`connector-oauth-debug.log` 无 ardot 内容（242 B）。 |
| Ardot MCP server 端工具清单全量 | asar 里**没有**。只有 i18n 覆盖的 15 个 + 运行时日志出现的 8 个。完整清单在服务端。 |
| `ui://ardot-remote/editor.html` 的 HTML 本体 | **不在 asar**。运行时从 server `resources/read` 拉取（运行时日志 `htmlLen=634551`）。 |
| `setArdotEmbedUrlProvider` 的实现 | 是 **`function setArdotEmbedUrlProvider(e){}` 空函数**（死代码）。全 asar 仅 2 处引用（定义 + 导出映射），**无调用点**。`ardot.tencent.com/embed?file_id=` 这条 URL 形态的渲染路径**在当前构建里未接线**。 |
| `ardot_canvas` @ `82343723` | 该偏移落在 `/main/log-acl-guard.js` 的 `Zu=new Set([\`plan\`,\`overview\`,\`tasks\`,\`mcp_app\`,\`ardot_canvas\`,\`app\`])`（cloud artifact snapshot 的 kind 白名单），与 UI 渲染无关。 |
| `ardotFileAction` @ `90169007` | 落在 `/main/tar.js` 的 `getArdotFileAction()` 定义处（`rel=137549`），非 tar 逻辑。 |
| `ardot-canvas` @ `2968594` | asar **header 的文件名条目**，不是代码。真文件是 `/renderer/assets/ardot-canvas-yXuE0fe5.js`。 |
| `ardot_design_style` / `ardot_image_gen` 作为**工具** | **不是工具**。是 prompt 标签（§5.3）。`tar.js` 的 `repairLegacyAutoAttachedDesignSkills` 里也是当标签匹配。 |

---

## 8. 关键偏移量索引（速查）

| abs 偏移 | 文件 | 内容 |
|---|---|---|
| 2968594 | (asar header) | `ardot-canvas-yXuE0fe5.js` 文件名条目 |
| 74988933 | `/main/app-server-mcp-apps-host.js` | `custom-mcp:ardot` 常量，`var f=\`ardot\`` |
| 74992546 | 同上 rel=4154 | `async function fetchArdotAccessToken` |
| 74997581 | 同上 rel=9189 | `mcpApps:*` RPC 通道表 |
| 75014220 | 同上 rel=25828 | `isArdotHostAppRef` |
| 75057744 | 同上 rel=69352 | `createMcpAppsHost` |
| 75059791 | 同上 rel=71399 | `resolveMcpAppSurface` / `launchSurface` |
| 75069949 | 同上 rel=81557 | `ardot.getToken` **超时标签** |
| 75070533 | 同上 rel=82141 | `ardot.fetchAccessToken` **超时标签** |
| 75070693 | 同上 rel=82301 | `ardot.mcpConnect` **超时标签** |
| 75070612 | 同上 | `setManagedHeaders("ardot", {Authorization})` |
| 76356391 | `/main/daemon-app-server-main.js` | `ensureArdotDesignPromptContract` |
| 85784682 | `/main/origins.js` | `isArdotHostname` / 域名白名单 |
| 85785561 | 同上 rel=3416 | `/mcp-apps/sandbox-proxy.html` + 内嵌 HTML |
| 78872520 | `/main/dev-env-override.js` | `ARDOT_DEV_ENV_ENDPOINTS`（prod/test） |
| 81816719 | `/main/log-acl-guard.js` | `mapType` → `ardot_canvas` ⇒ `preview` |
| 82343723 | 同上 | `Zu` 集合含 `ardot_canvas`（cloud snapshot） |
| 85198350 | `/main/netdrive-env-route.js` | `ardotFileAction` 读入 OTel span |
| 85648827 | `/main/node.js` | `Yn` ardot skill 配对表 |
| 85649396 | 同上 | `repairLegacyAutoAttachedDesignSkills` |
| 86000461 | `/main/server-side-access-token.js` | `var a="https://ardot.tencent.com/mcp"` |
| 86000619 | 同上 | `buildBuiltinArdotMcpConfig` / `type:"http"` |
| 86001730 | 同上 | `resolveArdotMcpConfig` |
| 86491043 | `/main/server.js` | `displayTypeByKind` → `ardot_canvas` ⇒ `html` |
| 86805893 | 同上 rel=701130 | `expandMcpAppToolNameAliases` |
| 86842641 | 同上 rel=737878 | `SdkMcpRemoteTransport`（streamable→sse 回退） |
| 86846826 | 同上 rel=742063 | `createAuthorizingFetch`（Bearer 注入） |
| 86852056 | 同上 rel=747293 | `authorizationHeaders` |
| 86869169 | 同上 rel=764406 | `zl` 表 + `FirstPartyMcpProvider` 起点 |
| 86869497 | 同上 rel=764734 | `FirstPartyMcpProvider=class` |
| 86873707 | 同上 | `resolveTransport` → ardot `kind:"http"` |
| 86875820 | 同上 | `WorkbuddyMcpServer`（connector-proxy） |
| 86888484 | 同上 rel=783721 | `isMcpItemVisibleInSession`（welcomeModes 门控） |
| 86937509 | 同上 rel=832746 | `prefixedToolName:\`mcp__${i}__${o.name}\`` |
| 87192253+ | 同上 | i18n `tool_executing.ardot_*` |
| 88686181 | 同上 rel=2581418 | **`ArdotManager=class`** |
| 88726883 | 同上 rel=2622120 | `setManagedAuthRefresher("ardot",...)` 接线 |
| 88833633 | 同上 | `present_files` 描述里禁用 `ardot.tencent.com/file/` |
| 90168968 | `/main/tar.js` | `getArdotFileAction` |
| 90901801 | 同上 rel=870382 | `ArdotDesignStyleSection` |
| 90904689 | 同上 rel=873270 | `ArdotFileDirectiveSection` |
| 90906406 | 同上 rel=874987 | `ArdotImageGenSection` |

**非 asar（unpacked）关键文件**：
- `/renderer/assets/ardot-canvas-yXuE0fe5.js`（5,067 B）
- `/renderer/assets/share-ardot-virtual-Dk-KKAKA.js`（2,863 B）
- `/renderer/assets/contexts-DgzzFjnf.js`（7,553,508 B，`McpAppSandboxFrame`）
- `/renderer/assets/detail-panel-wrapper-BZPa078h.js`（57,517 B，面板 props）
- `/renderer/assets/artifact-slot-panel-DlzKPlfh.js`（57,517 B，`EnableArdot`）
- `/resources/mcp-app-preload.js`（10,220 B）
- `/resources/plugins/workbuddy-builtin/builtin-plugins/ardot/**`
- `/cli/dist/codebuddy-lite-wb.mjs`（9,485,196 B，`mcp__<server>__<tool>` 命名）
- 同级 `resources/wb-guest-sdk/wb.js`（18,729 B，extension 用，**Ardot 不用**）

---

## 9. 复现方法（脚本）

**① 建 asar 索引**（一次即可）

```js
const fs=require("fs");
const b=fs.readFileSync(".../resources/app.asar");
const jsonLen=b.readUInt32LE(12);
const header=JSON.parse(b.toString("utf8",16,16+jsonLen));
const base=8+b.readUInt32LE(4);                       // ← 正文起点
const map=new Map();
(function walk(n,p){for(const[k,v]of Object.entries(n.files||{})){
  const fp=p+"/"+k; if(v.files)walk(v,fp); else map.set(fp,v);}})(header,"");
// map: "/main/server.js" -> { size:"3600054", offset:"86104763"[相对 base] }
```

**② 反查任意 abs 偏移属于哪个文件**

```js
for(const [fp,v] of map){
  const s = base + Number(v.offset);
  if (abs >= s && abs < s + Number(v.size))
    console.log(fp, "rel=", abs - s);
}
```

**③ 按文件切片**

```js
const t = map.get("/main/server.js");
process.stdout.write(b.subarray(base+Number(t.offset), base+Number(t.offset)+Number(t.size)));
```

**④ 沿调用链 grep 关键字**（比 dd 盲切准得多）

```bash
node fgrep.js 'ardot\.tencent\.com/mcp' 600 6
node fgrep.js 'prefixedToolName' 300 10
node fgrep.js 'connector/oauth/\$\{' 300 20
```

**⑤ 运行时交叉验证（只读）**

```
~/.workbuddy/logs/mcp-apps-diag.log     # rpc.getToken / openHostApp.appResolved / prefixedToolName
~/.workbuddy/logs/daemon.log            # [ArdotManager] status response {"status":"connected"}
~/.workbuddy/mcp.json                   # 实测只有 rea，无 ardot → 证明内存注入
~/.workbuddy/logs/mcp-runtime/builtin_ardot-92a2c8dc/   # 存在但为空
```

---

## 10. 最终判断（回答你的 5 个问题）

**Q1 MCP 连接管理**
`ardot.getToken` / `ardot.fetchAccessToken` / `ardot.mcpConnect` **不是 IPC channel**，是 `withTimeout()` 的**超时标签**。真正的 RPC 前缀是 `mcpApps:*`（20 个方法）。调用者是 **daemon 主进程**（`app-server-mcp-apps-host.js`，在 `ensureUnknownAppReady` 里对 `ardot/*` appId 做重连 + 换 token）。agent 侧是 `mcpManager.connect()`。连接的是 **`https://ardot.tencent.com/mcp` 的 MCP Streamable HTTP**（`StreamableHTTPClientTransport`），4xx(非401/403) 时回退 `SSEClientTransport`。token 注入 **`Authorization: Bearer <token>`**，通过自定义 `createAuthorizingFetch` 在**每个请求**上动态 `Headers.set()`，token 源是 `firstPartyMcpProvider.managedHeaders.get("ardot")`。

**Q2 鉴权**
**不是 MCP 标准 OAuth，没有 PKCE，没有 client_id，没有 scope。** 走 WorkBuddy **connector-OAuth 影子账号代理**：`GET /v2/as/connector/oauth/{id}/status` → `POST .../connect`（body `{code:"shadow_account_grant"}`，`redirect:"manual"`，v2 失败退 console 路由）→ `GET .../accesstoken[?forceRefresh=1]` 拿 `{access_token|token, expire_at}`（秒→毫秒）。**token 不落盘**，只存在内存 `managedHeaders` + `readyUntil` 有效期窗口；401/403 触发 `refreshAccessToken()`（`forceRefresh`）并重连 MCP。异常路径：若「已 connected 但拿不到 token」→ `revoke` → 重新 `connect` → 重新取 token。

**Q3 右侧面板/画布**
`ardot_canvas` / `ardot-canvas` 是 **artifact 类型**（地图到 `preview`/`html`），不是 iframe 名。真实实现是 **MCP Apps**：server 通过 `tool._meta.ui.resourceUri = ui://ardot-remote/editor.html` 声明 UI，并在 `_meta.workbuddy.ui.launchSurface = "panel"` 声明进右侧面板。host 起**本地 HTTP server**（`http://127.0.0.1:<port>/mcp-apps/<instance>/app.html` + `/mcp-apps/sandbox-proxy.html`），renderer 建**两层 iframe**（外层 sandbox-proxy 持 CSP，内层跑 Ardot HTML），用标准 **MCP Apps `postMessage` JSON-RPC 2.0** 通信（**不是 MessageChannel，不是 wb-guest-sdk**）；Electron `<webview>` 场景另有 `mcp-app-preload.js` 做 capture-phase 截获。host-side 注册 API 就是 **`mcpApps:*` 那 20 个 daemon RPC**；面板 slot 在 renderer 侧叫 `mcpAppStandardPanelSlot` / `detailMainOverlay`，名字是**通用的 MCP App slot**（不叫 ardot），ardot 只是它的一个 app。

**Q4 技能注入**
**打包在 asar 里**（`/resources/plugins/workbuddy-builtin/builtin-plugins/ardot/skills/**`，7 个 skill 共 30 个文件，全部 unpacked），**不走服务端下发**。通过 `marketplace.json` 的 `builtin-plugin` 条目 + plugin manifest `"skills":"./skills"` 投递。技能里用 **HARD RULE 段落**（"All canvas manipulation MUST go through ardot MCP tools"）+ description 里 `disable-model-invocation: true` 强制加载。**另外 host 侧还有 3 条 every_turn 硬注入**：`<ardot_file_directive action="create|open|ambiguous">`、`<ardot_design_style>`、`<ardot_image_gen>`，以及 `welcomeMode==="design"` 时给 system prompt 尾部追加 `<ardot-design-skill-requirement>` 契约。技能配对表 `Yn` 硬编码在 `main/node.js`。

**Q5 工具命名**
**MCP server 返回裸名**（`create_design`、`batch_edit`…），**不带 `ardot_`**。
**host 加的是 `mcp__ardot__`**：`prefixedToolName = \`mcp__${serverId}__${toolName}\``（`server.js` `abs=86937509`），模型看到的就是这个（运行时日志实证）。
`ardot_create_design` 是 **host 侧扁平别名**，由 `expandMcpAppToolNameAliases()` 双向展开，主要用于 **i18n / UI 文案 / 日志 / artifact 归属判定**，不是 MCP 层真名。
`ardot_design_style` 和 `ardot_image_gen` **根本不是工具**，是 prompt 标签。

---

## 11. 架构图（文字版）

```
┌─ Electron renderer ────────────────────────────────────────────────┐
│  detail-panel-wrapper / artifact-slot-panel                        │
│    └─ mcpAppStandardPanelSlot                                      │
│         └─ McpAppSandboxFrame (contexts-DgzzFjnf.js)               │
│              ├ iframe#1  sandbox-proxy.html?csp=…  (127.0.0.1)     │
│              │    └ iframe#2  app.html  ← Ardot 编辑器 HTML        │
│              │         (postMessage JSON-RPC 2.0 / ui/* 方法)      │
│              └ bridge → mcpApps:*                                  │
└──────────────────────────┬─────────────────────────────────────────┘
                           │ Electron IPC / daemon RPC
┌──────────────────────────▼─ Electron main (daemon) ────────────────┐
│ app-server-mcp-apps-host.js                                        │
│   McpAppsGateway: instances / sessions / getToken / callTool        │
│   StaticHtmlHttpServer: /mcp-apps/<id>/app.html                    │
│   sandbox proxy: /mcp-apps/sandbox-proxy.html                      │
│                                                                    │
│ server.js                                                          │
│   FirstPartyMcpProvider                                            │
│     ardot → {type:"http", url:"https://ardot.tencent.com/mcp",     │
│              auth:{mode:"static"},                                 │
│              dynamicHeaders → managedHeaders.get("ardot")}         │
│   RemoteMcpClientController                                        │
│     SdkMcpRemoteTransport: streamable → (4xx≠401/403) → sse        │
│     createAuthorizingFetch: 每请求 Headers.set(Authorization)       │
│   ArdotManager                                                     │
│     status / connect / accesstoken / revoke  → WorkBuddy backend   │
│     applyBearerAndEnsureConnected → setManagedHeaders + connect    │
└──────────────────────────┬─────────────────────────────────────────┘
                           │ HTTPS + Authorization: Bearer
┌──────────────────────────▼─────────────────────────────────────────┐
│ https://ardot.tencent.com/mcp   (MCP Streamable HTTP)              │
│   tools: create_design / open_design / batch_edit / …              │
│   tool._meta.ui.resourceUri = ui://ardot-remote/editor.html        │
│   tool._meta.workbuddy.ui.launchSurface = "panel"                  │
└────────────────────────────────────────────────────────────────────┘

鉴权旁路（不是 MCP OAuth）：
daemon ──(WorkBuddy 会话凭据)──▶ www.workbuddy.cn
         GET  /v2/as/connector/oauth/{id}/status
         POST /v2/as/connector/oauth/{id}/connect   {code:"shadow_account_grant"}
         GET  /v2/as/connector/oauth/{id}/accesstoken[?forceRefresh=1]
                                      ▼ {access_token, expire_at}
```

---

*报告生成：只读分析，未修改 WorkBuddy 任何文件。*
