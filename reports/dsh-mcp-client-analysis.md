# DSH MCP 客户端能力评估 & Ardot MCP 接入方案

> 调研对象：`@deepseek-ai/dsh-mcp-client@0.2.1-rc.5`（DSH 0.2.1-rc.5）
> 调研时间：2026-11-08
> 调研方式：只读安装目录源码 + 只读用户配置 + 直连 Ardot 网关实测（含真实 OAuth 刷新、真实 tool call）
> 所有结论均附「文件路径 + 代码/原文片段」。找不到证据的写「未找到」。

---

## 0. 结论速览

| 问题 | 结论 |
|---|---|
| 传输协议 | 只支持 `stdio` 和 `streamable-http`。**不支持旧版 SSE 传输**（SDK 里有 `SSEClientTransport`，但插件从不构造它） |
| 配置写在哪 | `cordis.patch.yml`（三层叠加：bundle → profile → `$DSH_HOME`），本机实际写在 **bundle 层**（`default-workspace/ardot-mcp/cordis.patch.yml`） |
| `mcp__` 前缀 | 硬编码在 `publicToolName()`，**不可配置、不可去除、没有 `toolPrefix` 选项** |
| 注册作用域 | 由 `scopeOf(ctx)` 决定：宿主级行 → 全局；`@deepseek-ai/dsh-agent-preset` 内的行 → 预设作用域。`serverName` 是**按作用域预约**的（已验证） |
| OAuth | **完全不支持**（无 `authProvider` 透传、无 401 挑战处理、无刷新） |
| 自定义 header | 支持（静态 `headers`），但**只在插件激活时求值一次** —— 这是当前方案会静默失效的根因 |
| 从文件读 token | 可以，通过 Loader 的 `!!js` 表达式（当前就是这么做的） |
| token 刷新 | 插件本身**没有**；当前靠手工跑 `ardot-auth.mjs refresh` |
| `ardot-remote` 来源 | 已定位：`@local/ardot-mcp` bundle，`default-workspace/ardot-mcp/` |
| 当前可用性 | **可用**，实测 28 工具、tool call 成功（`fetch_guidelines` 返回 23596 字符真实正文） |
| 懒加载 / 工具过滤 | 插件层**完全没有**；只有 `dsh-tools` 的按 Agent `restrict()` 和 `deferLoading`（后者 MCP 桥不设置） |
| schema 成本 | 28 工具 / **57,671 字节** / 约 **16.5k tokens**（实测） |

**最重要的发现**：当前 Ardot 接入能工作纯属"计时器还没响"。`headers` 里的 `!!js` 表达式在插件激活时求值一次就固化了，token 过期后必须手工 `refresh` + 手工重载插件；而且**没有任何机制保证这件事发生**（无计划任务、无守护进程、无代码引用 `ardot-auth.mjs`）。报告最后给出三种可行方案。

---

## 1. dsh-mcp-client 的配置形态

### 1.1 声明一个 MCP server：一个插件实例 = 一个 server

```yaml
# 最小配置（README.md:34-53）
- id: mcp-github
  name: '@deepseek-ai/dsh-mcp-client'
  config:
    serverName: github
    transport: stdio
    command: npx
    args: ['-y', '@modelcontextprotocol/server-github']
    env:
      GITHUB_TOKEN: !!js process.env.GITHUB_TOKEN

- id: mcp-web
  name: '@deepseek-ai/dsh-mcp-client'
  config:
    serverName: web
    transport: streamable-http
    url: http://localhost:3000/mcp
    headers:
      Authorization: !!js '`Bearer ${process.env.MCP_TOKEN}`'
```

源码注释确认这个模型（`lib/index.js:748-751`）：

```js
 * MCP client bridge plugin: connects to an external MCP server and registers
 * its tools on `ctx.tools` under server-qualified public names
 * (`mcp__<serverName>__<rawName>`). Each plugin instance connects to one MCP
 * server; load multiple instances in `cordis.yml` for multiple servers.
```

### 1.2 传输协议：只有两种

`lib/index.js:38-48` —— 整个 transport factory 只有两个分支：

```js
function createTransport(config) {
	switch (config.transport) {
		case "stdio": return new StdioClientTransport({
			command: config.command,
			args: config.args,
			env: buildChildEnv(config.env),
			cwd: config.cwd
		});
		case "streamable-http": return new StreamableHTTPClientTransport(new URL(config.url), { requestInit: { headers: config.headers } });
	}
}
```

`Config` 的 union 也只有这两个 tag（`lib/index.js:781-801`）：

```js
const Config = z.union([z.object({
	transport: z.const("stdio"),
	...
}), z.object({
	transport: z.const("streamable-http"),
	...
})]);
```

**不支持旧版 HTTP+SSE 传输。** 佐证：MCP SDK 本身导出了 `SSEClientTransport`（`@modelcontextprotocol/client/dist/index.mjs:5026` 等多处），但 `dsh-mcp-client` 的 import 列表（`lib/index.js:4-6`）只引入 `Client, StreamableHTTPClientTransport, specTypeSchemas` 和 `StdioClientTransport`，从不构造 SSE transport。

> 实际影响：如果某个 MCP server 只提供旧版 `/sse` 端点，直接接不进来，需要本地 stdio 垫片做协议转换。

### 1.3 完整字段表（含实测校验的默认值）

| 字段 | 默认 | 说明 | 证据 |
|---|---|---|---|
| `transport` | 必填 | `stdio` \| `streamable-http` | `lib/index.js:782,793` |
| `serverName` | 必填 | `[A-Za-z0-9_-]{1,32}`，同一注册作用域内唯一 | `lib/index.js:768` |
| `command`/`args`/`env`/`cwd` | — | stdio | `lib/index.js:784-787` |
| `url`/`headers` | — | streamable-http，header 直接进 `requestInit` | `lib/index.js:46,795-796` |
| `toolCallTimeoutMs` | 60000 | 单次 tools/call 或 resource 请求 | `lib/index.js:766,788` |
| `maxInstructionBytes` | 32768 | 服务器 instructions 的 UTF-8 上限，超限**拒绝连接** | `lib/index.js:441,790` |
| `failOnStartupError` | false | 首次连接/同步失败是否让插件激活失败 | `lib/index.js:789` |
| `reconnect.enabled` | true | | `lib/index.js:434-439` |
| `reconnect.initialDelayMs` | 500 | 翻倍退避起点 | 同上 |
| `reconnect.maxDelayMs` | 30000 | 上限；也是"连接稳定"阈值 | 同上 |
| `reconnect.maxAttempts` | 10 | 连续失败上限，超过则**注销全部工具并停止重连** | 同上 |

`maxInstructionBytes` 值得注意：Ardot 当前 `instructions` 为空（实测 `instructions bytes: 0`），所以不触发。

### 1.4 配置写在哪：三层叠加，本机走的是 bundle 层

`dsh-app-boot/lib/index.js:1127-1133`：

```js
function readProfilePatches(binName, context, initialProfile) {
	const patches = structuredClone([
		...readBundlePatches(binName, context, initialProfile),
		...initialProfile?.patches ?? loadOptionalPatches(binName, context.patchPath) ?? [],
		...loadOptionalPatches(binName, join(context.home, "cordis.patch.yml")) ?? [],
		...context.overlays
	]);
```

即：**bundle 的 `cordis.patch.yml` → profile 的 `cordis.patch.yml` → `$DSH_HOME/cordis.patch.yml` → `--patch` 覆盖层**。

本机 Ardot 实际在**第一层**（bundle）：

```yaml
# C:\Users\bx233\Documents\deepseek-harness\default-workspace\ardot-mcp\cordis.patch.yml:7-17
- insert:
    - id: ardot-mcp
      name: '@deepseek-ai/dsh-mcp-client'
      config:
        serverName: ardot-remote
        transport: streamable-http
        url: https://ardot.tencent.com/mcp
        headers:
          Authorization: !!js '(() => { try { return "Bearer " + process.getBuiltinModule("node:fs").readFileSync("C:/Users/bx233/.dsh/ardot-mcp/access-token.txt", "utf8").trim() } catch { return "" } })()'
        toolCallTimeoutMs: 180000
        failOnStartupError: false
```

这个 bundle 通过 `package.json` 挂进 profile：

```json
// C:\Users\bx233\.dsh\profiles\desktop\package.json:7,36
"@local/ardot-mcp": "link:C:/Users/bx233/Documents/deepseek-harness/default-workspace/ardot-mcp",
...
"bundles": [ ... , "@local/ardot-mcp", ... ]
```

```json
// ardot-mcp/package.json:18-22
"dsh": {
  "bundle": { "patch": "./cordis.patch.yml" }
}
```

> **注意 `insert` 语义**（`cordis-plugin-include/lib/index.js:68-84`）：`- insert:` 无 `id` 时是**追加到根条目列表**；带 `id` 时是把子条目推进那个 `group` 的 `config` 数组。这决定了作用域：追加到根 = 宿主级 = 全局工具层。

### 1.5 `!!js` 是怎么求值的（理解 token 失效问题的关键）

`cordis-plugin-loader/lib/index.js:232-244`：

```js
const evaluate = new Function("ctx", "expr", `
  with (ctx) {
    return eval(expr)
  }
`);
function interpolate(ctx, value) {
	if (isJsExpr(value)) return evaluate(ctx, value.__jsExpr);
	...
}
```

它挂在一个 waterfall 上（`cordis-plugin-loader/lib/index.js:607-612`）：

```js
ctx.on("internal/config", function(_config, next) {
	const config = next();
	if (!this.entry || this.parent.fiber?.entry === this.entry) return config;
	if ((this.runtime?.callback)?.[EntryGroup.key]) return config;
	return interpolate(this.ctx, config);
}, { global: true });
```

`internal/config` 在每次 **fiber 启动/更新时** 触发（`cordis/lib/index.js:1346`），所以表达式是**每次激活求值一次**，不是每次 HTTP 请求。这就是为什么 README 要写「换 token 后要重新激活这一行」。

**实测佐证**：本机 `access_token` 已从 2026-10-26 的旧值刷新成新值，但旧值仍能成功 initialize（HTTP 200，Ardot 不因 rotate 而吊销旧 access token）。这**掩盖**了问题——但它意味着一旦真过期就不会有兜底。

---

## 2. 工具命名与作用域

### 2.1 `mcp__<server>__<tool>` 是谁加的

`lib/index.js:96-102` —— `publicToolName()`，纯函数：

```js
function publicToolName(serverName, rawName) {
	const joined = `mcp__${serverName}__${rawName}`;
	const normalized = joined.replace(INVALID_NAME_CHARS, "_");
	if (normalized === joined && normalized.length <= MAX_PUBLIC_NAME_LENGTH) return normalized;
	const hash = createHash("sha256").update(`${serverName}\0${rawName}`).digest("hex").slice(0, HASH_LENGTH);
	return `${normalized.slice(0, MAX_PUBLIC_NAME_LENGTH - HASH_LENGTH - 1)}_${hash}`;
}
```

常量（`lib/index.js:68,70,72`）：

```js
const MAX_PUBLIC_NAME_LENGTH = 64;   // DeepSeek function-name 契约
const INVALID_NAME_CHARS = /[^A-Za-z0-9_-]/g;
const HASH_LENGTH = 12;
```

### 2.2 能不能改成不带前缀 / 自定义前缀？

**不能。** 三重证据：

1. `mcp__` 是 `publicToolName()` 里的模板字符串字面量，没有任何参数或配置能影响它（`lib/index.js:97`）。
2. `Config` schema 里**没有任何**前缀相关字段——完整字段就是 §1.3 表格那些（`lib/index.js:781-801`）。
3. 全仓库 grep `toolPrefix` / `tool_prefix` 在 `@deepseek-ai/*/lib/*.js` 下**零命中**：

```
$ grep -rl "toolPrefix\|tool_prefix" @deepseek-ai/*/lib/*.js
(empty = none)
```

> README 明确把它当作**钉死的契约**（`README.md:108`）：「Naming is a pinned contract. Public names are pure functions of `(serverName, rawName)` … changing it after release would break session history and permission rules.」
> 而且原始名才是线上名（`README.md:109`）：「The raw name is the only wire name. `tools/call` always receives the raw name」，所以即使你改外层名，也不会影响实际调用。

**唯一能做的是改 `serverName`**，它就是你唯一可控的那一段。当前是 `ardot-remote` → `mcp__ardot-remote__fetch_guidelines`。

**降级路径**：如果确实需要无前缀工具名，只能在 MCP 桥之上再写一层工具代理（native tool 转发到 `mcp__*`），但那是另一个包的工作量，不是配置能解决的。

### 2.3 注册作用域怎么控制

**机制**（`lib/index.js:812-822`）：

```js
ctx.effect(() => {
	const owner = scopeOf(ctx) ?? ctx.root;
	let names = activeServerNames.get(owner);
	if (!names) {
		names = /* @__PURE__ */ new Set();
		activeServerNames.set(owner, names);
	}
	if (names.has(config.serverName)) throw new Error(`mcp-client: serverName "${config.serverName}" is already in use by another mcp-client instance — pick a unique serverName in cordis.yml`);
	names.add(config.serverName);
	return () => void names.delete(config.serverName);
}, "mcp-client.serverName");
```

`scopeOf(ctx)`（`dsh-scope/lib/index.js:312-314`）返回最近继承的 scope tag，`undefined` 表示无作用域：

```js
function scopeOf(ctx) {
	return ctx[kScope];
}
```

**用户 `cordis.patch.yml` 里那条说法已验证为真**：

> 「dsh-mcp-client 按注册作用域预约 `serverName` 并把工具注册进该作用域」

对应源码就是上面 `activeServerNames` 这个 `WeakMap`（按 owner 分桶）+ `ctx.tools.register()` 注册进 `ctx` 的作用域。原文（`C:\Users\bx233\.dsh\cordis.patch.yml:8-9`）：

```yaml
# dsh-mcp-client 按注册作用域预约 `serverName` 并把工具注册进该作用域，因此
# REA 工具只对选中 pruge-rea 的 Agent 可见，其他所有模式连一个 REA 工具都看不到。
```

预设作用域的建立点是 `dsh-agent-preset-registry/lib/index.js:495-505`：

```js
async activate(record) {
	const key = {};
	const scope = createScope(this.owner, key);
	try {
		...
		const context = scope.ctx.extend({ baseUrl: record.context.baseUrl });
		const mount = await mountPreset(context, record.config.id, record.config.plugins);
```

即 `@deepseek-ai/dsh-agent-preset` 的 `plugins:` 数组整体挂在一个新建 scope 下，所以其中的 `dsh-mcp-client` 行注册的工具是**预设作用域局部**的，不进全局层。`dsh-agent-preset` 自己只是把 `config.plugins` 交给 registry（`dsh-agent-preset/lib/index.js:24-26`）：

```js
async *[Service.init]() {
	yield await this.ctx.agentPresets.register(this.config);
}
```

**两种作用域写法对照**：

| 想要 | 写法 | serverName 预约范围 |
|---|---|---|
| 全局（所有 preset 都看得见） | 根条目列表 `- insert: [{...}]`（当前 Ardot 就是这样） | 全局层，与任何其他全局行冲突 |
| 仅某 preset | `@deepseek-ai/dsh-agent-preset` 的 `config.plugins:` 里一行 | 该 preset 的 scope，可与别处同名 |

预设作用域的真实例子（`pruge-mode/cordis.patch.yml:162-190`）：

```yaml
- id: preset-pruge-rea
  name: '@deepseek-ai/dsh-agent-preset'
  config:
    id: pruge-rea
    ...
    plugins:
      - id: rea-mcp
        name: '@deepseek-ai/dsh-mcp-client'
        config:
          serverName: rea
          transport: stdio
          command: C:\\Program Files\\nodejs\\node.exe
          args: [...]
```

**关键差异**：预设作用域的行**不能**用来做「跨 preset 共享」，而且 README 说「Independent Agent scopes may reuse the same namespace because their tools and transports are isolated」（`README.md:127`）——即同名 `serverName` 在**不同 preset 里可以复用**，但在同一 scope 内重复会直接抛错。

### 2.4 工具可见性的二次裁剪（不是 mcp-client 的能力）

`dsh-tools` 提供 per-Agent 掩码（`dsh-tools/README.md:85`）：

```
`ctx.tools.restrict(filter)` applies an allow or deny mask to the global tools one agent inherits; masks intersect, scoped registrations stay visible, and the restriction lifts when disposed.
```

注意「**scoped registrations stay visible**」——`restrict()` 只能筛**继承来的全局工具**，筛不掉本 scope 自己注册的。这正是 `pruge-mode/guard.js:37-42` 那条注释说的：

```js
 * REA is the one entry that belongs here — its ~200 schemas are the reason
 * this guard exists — so `pruge` denies `mcp__rea__*` while `pruge-rea` does
 * not. ... `restrict()` filters what a scope inherits and never what its own chain
 * registers, and a scopeless `schemas()` read sees only the global layer. The
 * deny entry is therefore inert on `pruge-rea` by construction.
```

`restrict()` 实现要点（`dsh-tools/lib/index.js:2991-3005`）：必须有 scope（`agent.ctx`），否则直接抛：「a context-global restriction would mask every agent」；且不能命名未知工具、不能动 `run_code`。

---

## 3. 鉴权

### 3.1 支持 OAuth 吗？——**不支持**

三重证据：

**(a) 插件从不传 `authProvider`。** `lib/index.js:46` 构造 transport 时只给了 `requestInit.headers`：

```js
case "streamable-http": return new StreamableHTTPClientTransport(new URL(config.url), { requestInit: { headers: config.headers } });
```

而 MCP SDK 的 OAuth 完全依赖这个选项（`@modelcontextprotocol/client/dist/index.mjs:5047-5064`）：

```js
constructor(url, opts) {
	...
	this._requestInit = opts?.requestInit;
	...
	if (isOAuthClientProvider(opts?.authProvider)) {
		this._oauthProvider = opts.authProvider;
		this._authProvider = adaptOAuthProvider(opts.authProvider, { skipIssuerMetadataValidation: opts.skipIssuerMetadataValidation });
		if (opts.authProvider.dpop) {
			this._dpop = withDpopFromProvider(opts.authProvider);
			this._fetch = this._dpop(opts.fetch ?? fetch);
		}
	} else this._authProvider = opts?.authProvider;
```

没有 `authProvider` → `_authProvider` 为 undefined → SDK 的 401 挑战/动态注册/刷新整条链路全部失效。

**(b) `Config` schema 无任何 auth 字段**（`lib/index.js:781-801`，见 §1.3 完整表）。

**(c) 文档明确否定**（`ardot-mcp/README.md:16`）：

> `@deepseek-ai/dsh-mcp-client` 只支持静态 `headers`，自己不跑 OAuth（没有动态注册、没有 401 挑战处理、也不会刷新）。

**Ardot 侧确实要求 OAuth**（实测 401 挑战）：

```
$ curl -X POST https://ardot.tencent.com/mcp  (无 Authorization)
401
WWW-Authenticate: Bearer realm="mcpgateway", error="invalid_request",
    error_description="missing bearer token",
    resource_metadata="https://ardot.tencent.com/.well-known/oauth-protected-resource/mcp", scope="mcp:use"

$ (Authorization: Bearer not-a-token)
401
WWW-Authenticate: ... error="invalid_token", error_description="token is inactive", ...
```

Ardot 的 AS 元数据（实测 `/.well-known/oauth-authorization-server`）：

```json
{
 "issuer": "https://ardot.tencent.com",
 "authorization_endpoint": "https://ardot.tencent.com/oauth/authorize",
 "token_endpoint": "https://ardot.tencent.com/oauth/token",
 "registration_endpoint": "https://ardot.tencent.com/oauth/register",
 "grant_types_supported": ["authorization_code", "refresh_token"],
 "code_challenge_methods_supported": ["S256"],
 "scopes_supported": ["mcp:use"],
 "token_endpoint_auth_methods_supported": ["none"]
}
```

→ 标准 MCP OAuth：RFC 9728 资源元数据 + RFC 8414 AS 元数据 + RFC 7591 动态注册 + 授权码+PKCE + refresh_token。SDK 完全能跑这套，**只是 DSH 这一层没把 `authProvider` 暴露出来**。

### 3.2 支持自定义 Authorization header / bearer token 吗？——**支持，但是静态的**

`headers: z.dict(String)`（`lib/index.js:796`）直接透传给 `requestInit`，所以任何 header 都能加：

```yaml
headers:
  Authorization: Bearer eyJ...
  X-Custom: whatever
```

**但有个致命限制**：它只在**激活时求值一次**（见 §1.5）。所以：

- 写死 token → 过期即死，需要手工重载
- 写 `!!js` 读文件 → 每次激活重新读，但**运行期间不重读**

**另一个已验证的坑**：`headers` 的值**不能是 getter**。我用真实的 `@deepseek-ai/schemastery` 做了实测：

```
$ node test-schema.mjs   (用 getter 提供 headers.Authorization)
TypeError: Cannot set property Authorization of #<Object> which has only a getter
    at schemastery/lib/index.mjs:500:14  (data[rKey] = data[key])
```

→ 想用「getter 每次读文件」这种小聪明的路走不通，schema 校验会把 getter 拍平成普通属性。**必须靠外层刷新机制**。

### 3.3 能从文件读 token 吗？——**能，当前就是这么做的**

`cordis.patch.yml:15` 用的就是 `process.getBuiltinModule("node:fs").readFileSync(...)`。

为什么是 `getBuiltinModule` 而不是 `require`/`import`：`interpolate()` 用 `new Function("ctx", "expr", "with (ctx) { return eval(expr) }")` 求值（`cordis-plugin-loader/lib/index.js:233-237`），作用域里没有 `require`，`eval` 也不是 async 上下文，所以同步的 `process.getBuiltinModule('node:fs')` 是最稳的写法。实测可用。

### 3.4 有 token 刷新逻辑吗？——**没有**

- 插件：`grep -oin "oauth\|bearer\|authorization\|refresh" lib/index.js` 只命中 `refresh`（那是 tools/list 的 `cacheMode: "refresh"` 和 refreshTools，与 token 无关）。
- 现状靠外部脚本 `ardot-auth.mjs refresh`，**但没有任何东西在调用它**：

```
$ grep -rIl "ardot-auth" ~/Documents/deepseek-harness/default-workspace ~/.dsh --include="*.mjs" --include="*.js" --include="*.yml" ...
Documents/deepseek-harness/default-workspace/ardot-mcp/ardot-auth.mjs     <- 自己
Documents/deepseek-harness/default-workspace/ardot-mcp/cordis.patch.yml   <- 注释里提到
(END)

$ Get-ScheduledTask | Where-Object { $_.TaskName -match 'ardot|dsh|token' }
(no output)
```

→ **纯手工**。这就是本次报告要修的核心问题。

### 3.5 中间层方案可行吗？——**可行，已实现并实测通过**

因为 MCP 是标准协议、`transport: stdio` 又完全由 DSH 托管生命周期，写一个 stdio↔HTTP 垫片是最干净的解法。我实现了两个并都做了真实请求验证（见 §6）。

---

## 4. 本会话的 `mcp__ardot-remote__` 是哪来的

### 4.1 完整链路

```
C:\Users\bx233\.dsh\profiles\desktop\package.json
  └─ dependencies: "@local/ardot-mcp": "link:.../default-workspace/ardot-mcp"
  └─ dsh.profile.bundles: [..., "@local/ardot-mcp", ...]
       └─ ardot-mcp/package.json: dsh.bundle.patch = "./cordis.patch.yml"
            └─ ardot-mcp/cordis.patch.yml: id=ardot-mcp, name=@deepseek-ai/dsh-mcp-client
                 config.serverName = ardot-remote
                 config.url = https://ardot.tencent.com/mcp
                 headers.Authorization = !!js 读 access-token.txt
```

`ardot-remote` 这个字符串在 `C:\Users\bx233\.dsh\` 和 DSH 安装目录下**只出现在两处**（其余全是 session 缓存回放）：

```
$ grep -rIl "ardot-remote" ~/.dsh/ | grep -v session_projcache | grep -v /sessions/
(空)

$ grep -n "ardot" ~/.dsh/profiles/desktop/*.yml
(空)   # profile 的 patch 里没有，因为它在 bundle 层
```

它出现在：
1. `default-workspace/ardot-mcp/cordis.patch.yml:11` → `serverName: ardot-remote`
2. `default-workspace/ardot-mcp/README.md` → 说明文档

> 注意：`ardot-remote` **不是**远端 `serverInfo.name` 的直接产物——它恰好同名（实测 `serverInfo: {"name":"ardot-remote","version":"1.1.6-8d6d3d35"}`），但按 README:107「The namespace is local configuration, never the remote `serverInfo.name`」，纯粹是巧合。改远端名不会影响工具名。

### 4.2 当前能不能正常连？——**能**

实测（2026-11-08 13:0x）：

```
$ node /tmp/verify-new-token.mjs
token matches credentials: true
status 200
event: message
data: {"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{"listChanged":true},
"resources":{"listChanged":true}},"serverInfo":{"name":"ardot-remote","version":"1.1.6-8d6d3d35"}},"jsonrpc":"2.0","id":1}}
```

会话内真实调用（本报告写作过程中执行）：

```
mcp__ardot-remote__fetch_guidelines(topic="posters")
→ 返回 23,596 字符真实设计规范正文（"ROLE: You are a professional poster and cover designer..."）
```

工具清单（实测 28 个）：

```
batch_edit, locate_available_space, fetch_editor_state, fetch_file_info, apply_variables,
fetch_styles, apply_styles, export_variables, scan_exportable_resources, get_available_fonts,
create_new_page, search_style_guide, build_style_guide, register_assets, html_to_ardot,
use_dev_mode, fetch_component_lib, batch_read, capture_layout, capture_screenshot,
download_source_media, export_nodes, fetch_variables, fetch_guidelines, upload_images,
open_design, create_design, get_editor_token
```

**版本漂移**：本机历史快照 `pruge-check5.json`（2026-10-26）记录 **26** 个工具，当前远端是 **28** 个。新增的是 `apply_styles` 和 `use_dev_mode`。远端版本也从 `1.1.5-b29f1543` 升到 `1.1.6-8d6d3d35`。这说明 Ardot 侧会加工具，schema 成本会继续涨。

### 4.3 凭据现状

```json
// C:\Users\bx233\.dsh\ardot-mcp\credentials.json （脱敏）
{
  "client_id": "01a17891-5b80-72dd-bc0d-f9e031a1a537",
  "redirect_uri": "http://127.0.0.1:48213/callback",
  "issuer": "https://ardot.tencent.com",
  "scope": "mcp:use",
  "token_type": "Bearer",
  "access_token": "eyJ0eXAiOiJKV1QiLCJh…",     // len=1160, JWT
  "refresh_token": "def5020012a5d92b6771…",    // len=826
  "expires_at": "2026-11-23T05:05:51.201Z",    // 本次调研中刷新过
  "updated_at": "2026-11-08T05:05:51.208Z"
}
```

**本次调研期间的实测操作**（已备份为 `credentials.json.bak-20261108130114`）：

1. 跑 `node ardot-auth.mjs refresh` → 成功，`expires_at` 从 `2026-11-10T14:26:13Z` 变成 `2026-11-23T05:01:20Z`（`expires_in = 1296000` = **15 天**）
2. 验证 **refresh_token 会 rotate**：新旧 refresh_token 内容不同（都是 826 字符），所以**刷新必须落盘新 refresh_token**，否则下次刷新失败
3. 验证新旧 access_token **都能用**（Ardot 不因 rotate 吊销旧 access token）

→ 15 天有效期意味着**每月至少失效一次**，纯手工维护必翻车。

### 4.4 Ardot 工具的真实调用契约（实测发现的坑）

`Ardot` 的 `initialize` 响应**不带 `mcp-session-id`**，且 `notifications/initialized` 返回 202：

```
init status 200
mcp-session-id: null          <- 无状态服务
body: event: message / data: {"result":{...}}

POST notifications/initialized -> 202
POST tools/list -> 200
```

写垫片时不能依赖 session id 头（我第一版假设有，实测为 null）。当前实现是按需透传，没有也不报错。

---

## 5. MCP 工具的 schema 成本与懒加载能力

### 5.1 实测成本

```
tool count: 28
total schema chars: 57,671
approx tokens (chars/3.5): 16,477
```

Top 10 最大的 schema：

| 工具 | 字节 |
|---|---|
| `apply_styles` | 6,651 |
| `batch_read` | 3,856 |
| `search_style_guide` | 3,752 |
| `export_variables` | 3,678 |
| `export_nodes` | 3,160 |
| `fetch_styles` | 2,929 |
| `capture_screenshot` | 2,321 |
| `fetch_variables` | 2,209 |
| `build_style_guide` | 2,190 |
| `upload_images` | 2,043 |

**约 16.5k tokens 常驻**——这是每轮请求都付的成本。对比本机历史快照（`pruge-check5.json`）：

- `standard` preset：295 工具 / 888,542 JSON 字符
- `pruge` preset：85 工具 / 112,511 JSON 字符

Ardot 的 57k 字符占 `pruge` 的 **51%**。

**KV cache 影响**（`README.md:171`）：工具定义前缀在工具集不变时稳定；一旦 Ardot 加工具（如这次 26→28），前缀从第一个变化的 schema token 起失效。

### 5.2 插件层有懒加载 / 按需激活 / 工具过滤吗？——**全都没有**

逐项核验 `lib/index.js` 全文 836 行：

| 能力 | 有无 | 证据 |
|---|---|---|
| 工具白/黑名单（`includeTools`/`excludeTools`/`allowTools`） | **无** | `grep -on "filter[A-Za-z]*\|includeTools\|excludeTools\|allowTools\|toolPrefix"` → 零命中 |
| `deferLoading` | **无** | `grep -c "deferLoading" lib/index.js` → **0** |
| 关键词/语义激活 | **无** | 无相关字段或代码 |
| 按需发现（`tools/list` 分页懒取） | **无** | 激活时 `listTools({cacheMode:"refresh"})` **全量**拉取（`lib/index.js:129`） |
| 只在需要时连接 | **无** | `apply()` 里 `await connection.ready`，激活即连接（`lib/index.js:832`） |

关键代码（`lib/index.js:127-161`）：激活时把远端**全部**工具一次性注册：

```js
async function syncTools(client, ctx, opts, previous) {
	const definitions = /* @__PURE__ */ new Map();
	const response = client.getServerCapabilities()?.tools === void 0 ? { tools: [] } : await client.listTools(void 0, { cacheMode: "refresh" });
	for (const tool of response.tools) {
		const publicName = publicToolName(opts.serverName, tool.name);
		...
		definitions.set(publicName, createMcpToolDefinition(ctx, {...}));
	}
	...
	for (const [publicName, definition] of definitions) disposers.set(publicName, ctx.tools.register(definition));
```

### 5.3 可用的替代手段（三层，按侵入性递增）

**手段 A：`ctx.tools.restrict()` 按 Agent 裁剪（零代码，已验证可用的模式）**

本机 `pruge-mode/guard.js` 和 `pruge-mcp-guard/index.js` 就是这个套路。`restrict({deny})` 或 `restrict({allow})` 能精确控制某个 Agent 看见哪些 `mcp__ardot-remote__*`。

代价：**schema 仍然被注册**（只是不进某 Agent 的请求），所以对全局常驻成本没帮助，但**能阻止 Ardot 的 57k 字符污染那些不需要它的 preset**。

当前实现细节（`pruge-mode/guard.js:76`）：

```js
const MCP_DENIED_PREFIXES = ['mcp__rea__']
```

→ 现在是 **deny-list**，只屏蔽 `mcp__rea__*`。**Ardot 全 preset 可见**。如果想在 e.g. `minimal` preset 里隐藏 Ardot，加一条 `mcp__ardot-remote__` 即可。

**手段 B：`deferLoading`（插件不设置，且语义不等于省 token）**

`dsh-tools` 会保留这个标记：

```
dsh-tools/README.md:240
`defineTool()`, registry schema projection, and system-prompt assembly preserve `deferLoading: true`. The marker requests deferred definition loading and does not imply a `tool-addition` record; the LLM package documents provider enforcement limits.
```

DeepSeek provider 下传成 `defer_loading: true`（`dsh-llm-deepseek/lib/index.js:1731`）：

```js
...tool.deferLoading === true ? { defer_loading: true } : {}
```

**但它有两个限制，使它对「降低 Ardot schema 成本」没用**：

1. **路由不支持时标记被整体丢弃。** `projectToolUpdates()` 的 fallback 分支（`dsh-llm/lib/index.js:786-793`）：
   ```js
   if (toolUpdate === void 0) {
       let immediateTools = tools;
       if (tools?.some((tool) => tool.deferLoading === true)) immediateTools = tools.map(({ deferLoading: _loading, ...tool }) => tool);
       return { messages: withoutDeveloperMessages(messages), tools: immediateTools };
   ```
   只要**有一个**工具标了 `deferLoading`，**所有**工具的该标记都被剥掉，变成全部立即加载。

2. **支持路由下，defer 的工具要等「加进历史」才激活。**（`dsh-llm/lib/index.js:759-764`）`toolDeclarations()` 只给 `update.additions` 里的工具补 `deferLoading: true`：
   ```js
   for (const update of history.updates) for (const tool of update.additions) if (!declarations.has(tool.name)) declarations.set(tool.name, { ...tool, deferLoading: true });
   ```
   而 `README.md:181` 明说：「Explicitly deferred baseline tools remain deferred until their first retained addition block; declaring a deferred tool does not activate it.」→ **基线里就标 defer 的工具反而不会被激活**，模型根本看不见它。

**结论**：`deferLoading` 是给「运行中动态新增的工具」用的、且依赖 provider 的 `toolUpdate` 能力，**不是**省 token 的手段。不要为省 Ardot 的 16.5k 走这条路。

**手段 C：真·按需激活（推荐，需写包）**

写一个 `@local/dsh-mcp-lazy` 插件：不在激活时注册全部工具，而是注册一个 `ardot_call` 分发工具（或按工具组注册 3-4 个入口），内部再转发到 `client.callTool({name: rawName})`。这样 28 个 schema 塌缩成 1 个，成本从 ~16.5k 降到 ~0.3k。

`createMcpToolDefinition()` 是**导出**的（`lib/index.js:836`），就是为这种复用设计的（`README.md:123`）：

```
The exported `createMcpToolDefinition(ctx, options)` adapts an upstream tool schema and raw-result callback to the same canonical values, errors, and durable image projection. ... Callers own registration, cancellation deadlines, and provider teardown.
```

代价：模型必须知道有哪些工具可调（需要一段 prompt 或 `tools/list` 的元信息），且丢失 per-tool 参数校验的早期反馈。

---

## 6. 三种落地方案对比

| 方案 | 改动量 | token 自动刷新 | 需要常驻进程 | 端口 | 实测状态 |
|---|---|---|---|---|---|
| **A. 现状**（`!!js` 读文件 + 手工 refresh + 手工重载） | 0 | ❌ 纯手工 | 否 | 无 | 当前可用，但**会随 token 过期静默失效** |
| **B. stdio 桥接**（推荐） | 1 个新文件 + 改 1 行配置 | ✅ 每请求检查+刷新 | 否（DSH 托管） | 无 | ✅ **已实现并实测通过** |
| **C. 本地 HTTP 代理** | 1 个新文件 + 改 1 行配置 | ✅ 每请求检查+刷新 | 是（需守护） | 48214 | ✅ **已实现并实测通过** |

两种实现都放在 `E:\better-zslf\reports\`：

- `ardot-stdio-bridge.mjs`（方案 B）
- `ardot-oauth-proxy.mjs`（方案 C）

### 6.1 方案 B 实测结果

```
$ node /tmp/test-bridge.mjs
STDERR: [ardot-bridge] started, upstream=https://ardot.tencent.com/mcp, credentials=...credentials.json
initialize: {"name":"ardot-remote","version":"1.1.6-8d6d3d35"} proto 2025-06-18
tools: 28 | first 5: batch_edit, locate_available_space, fetch_editor_state, fetch_file_info, apply_variables
tools/call fetch_guidelines -> chars: 23596 | head: ROLE: You are a professional poster and cover designer. GOAL: Produce posters/covers with a single hero focus, strong re
bridge exited cleanly
```

完整 MCP 握手（`initialize` → `notifications/initialized` → `tools/list` → `tools/call`）全部通过，`stdin.end()` 后干净退出（DSH dispose 时会这样关它）。

### 6.2 方案 C 实测结果

**冷启动 + 过期 token 自动刷新**（把 `expires_at` 手工改成过去时间再请求）：

```
$ node ardot-oauth-proxy.mjs
[proxy] listening http://127.0.0.1:48214/mcp -> https://ardot.tencent.com/mcp

$ node /tmp/probe-expiry.mjs
status after stale token: 200
data: {"result":{...,"serverInfo":{"name":"ardot-remote","version":"1.1.6-8d6d3d35"}}}

$ cat proxy-test3.log
[proxy] token refreshed, valid until 2026-11-23T05:05:51.201Z
[proxy] POST /mcp -> 200 (481ms)

$ (credentials.json 已更新，access-token.txt 同步)
expires_at now: 2026-11-23T05:05:51.201Z
token file == credentials.access_token: True
```

**自动刷新链路端到端验证通过**：发现过期 → 用 refresh_token 换新 → 原子落盘（含 rotate 后的新 refresh_token）→ 同步 `access-token.txt` → 用新 token 转发 → 200。

### 6.3 为什么方案 B 更好

| 维度 | B (stdio) | C (HTTP) |
|---|---|---|
| 进程生命周期 | DSH 管（spawn/dispose/重连/退避全部复用 `dsh-mcp-client`） | 你得自己守护（计划任务 / nssm / 开机启动） |
| 端口冲突 | 无 | 48214 被占就挂 |
| 崩溃恢复 | 插件 `reconnect` 策略自动重启子进程 | 手动或外部 watchdog |
| 安全面 | 无监听端口 | 本机任意进程可白嫖你的 Ardot 凭据 |
| 调试 | stderr + `bridge.log` | HTTP 日志 |
| 配置改动 | `transport: streamable-http` → `stdio` + `command`/`args` | 只改 `url` |

**唯一劣势**：多一层 JSON-RPC 转发（每次调用约 30–500ms 额外延迟，实测 `tools/list` 38ms、`initialize` 1.5s 冷启）。

---

## 7. 推荐的最终方案（把 Ardot MCP 稳定接进 DSH 并自动刷新 token）

### 步骤 1：放置桥接脚本

把 `E:\better-zslf\reports\ardot-stdio-bridge.mjs` 复制到 bundle 目录：

```powershell
Copy-Item "E:\better-zslf\reports\ardot-stdio-bridge.mjs" `
          "C:\Users\bx233\Documents\deepseek-harness\default-workspace\ardot-mcp\ardot-stdio-bridge.mjs"
```

（放进 bundle 目录而不是别处，是为了让 bundle 自包含——`cordis.patch.yml` 和桥接脚本一起搬走就能换机器。）

### 步骤 2：改 bundle 的 `cordis.patch.yml`

```yaml
# C:\Users\bx233\Documents\deepseek-harness\default-workspace\ardot-mcp\cordis.patch.yml
- insert:
    - id: ardot-mcp
      name: '@deepseek-ai/dsh-mcp-client'
      config:
        serverName: ardot-remote
        transport: stdio
        command: C:\Program Files\nodejs\node.exe
        args:
          - C:\Users\bx233\Documents\deepseek-harness\default-workspace\ardot-mcp\ardot-stdio-bridge.mjs
        toolCallTimeoutMs: 180000
        # 桥上会在 token 失效时自动刷新并重试，首次连接失败别拖垮 Host
        failOnStartupError: false
        reconnect:
          enabled: true
          maxAttempts: 10
```

关键点：
- `serverName: ardot-remote` **保持不变** → 工具名 `mcp__ardot-remote__*` 完全不变，已有 session 历史、权限规则、`pruge` guard 白名单全部无需改动。
- 删掉 `headers`（不再需要）。
- `command` 用**绝对路径**的 node（`C:\Program Files\nodejs\node.exe`），不要用裸 `node` —— `StdioClientTransport` 是 `spawn(command, args, {shell: false})`（`@modelcontextprotocol/client/dist/stdio.mjs` 的 `spawn(...)` 段），裸 `node` 依赖 PATH，Host 的 PATH 不保证一致。
- `env` 不用写：`buildChildEnv()` 会先 `scrubbedParentEnv()`（剔掉 `KEY|PASSWORD|SECRET|TOKEN` 和 `DSH_*`，`dsh-subprocess/lib/index.js:50-55`），桥接脚本自己从文件读凭据，不依赖环境变量。

### 步骤 3：重载插件（不需要重启 DSH）

```
plugin_manager  action=set_plugin  target=include:ardot-mcp  enabled=false
plugin_manager  action=set_plugin  target=include:ardot-mcp  enabled=true
```

（`setPluginEnabled` 实现在 `dsh-plugin-manager/lib/index.js:1930-1945`，它会 `writePluginEnabled` + `reload([patchId])`，即就地重建这一行。）

或者直接在设置里改这一行任意字段触发 HMR。

### 步骤 4：验证

```powershell
# 1. 桥接进程能起来
node "C:\Users\bx233\Documents\deepseek-harness\default-workspace\ardot-mcp\ardot-stdio-bridge.mjs"
# 期望 stderr: [ardot-bridge] started, upstream=https://ardot.tencent.com/mcp, ...

# 2. DSH 内工具还在
#    在会话里调 mcp__ardot-remote__fetch_guidelines(topic="table")
# 期望：返回真实设计规范正文（不是 "server is disconnected"）

# 3. 刷新链路
Select-String -Path "C:\Users\bx233\.dsh\ardot-mcp\bridge.log" -Pattern "token refreshed"
```

### 步骤 5：让刷新真正"自动"（可选，但建议）

方案 B 已经做到「每次请求检查 token，快过期就刷新」，所以**只要你在用 Ardot，token 永远不会过期**。但如果你 15 天没碰它，第一次调用时桥会自己刷新 —— 这已经覆盖了绝大多数场景。

**唯一需要人工介入的情况**：refresh_token 本身失效（Ardot 侧策略变更、账号改密、refresh_token 被吊销）。这时 `bridge.log` 会出现：

```
refresh failed HTTP 400: {"error":"invalid_grant", ...}
```

→ 重跑 `node ardot-auth.mjs login` 即可。

**想更进一步**（可选）：加一条空闲时的保活计划任务，把「15 天不用就失效」变成「永不过期」：

```powershell
# 每周一 09:00 刷一次，避免 refresh_token 长期不用被回收
$action = New-ScheduledTaskAction -Execute "C:\Program Files\nodejs\node.exe" `
  -Argument "C:\Users\bx233\Documents\deepseek-harness\default-workspace\ardot-mcp\ardot-auth.mjs refresh"
$trigger = New-ScheduledTaskTrigger -Weekly -DaysOfWeek Monday -At 9am
Register-ScheduledTask -TaskName "dsh-ardot-token-refresh" -Action $action -Trigger $trigger
```

> 注意：加了这条任务后，**桥和任务会竞争 refresh_token**（因为 Ardot 会 rotate）。当前桥实现了 inflight 串行化（同一进程内不会并发刷新），但跨进程（桥 vs 计划任务）没有锁。**建议只用桥，不加计划任务**——桥已经覆盖了所有真实使用场景，加计划任务反而引入竞争。

### 步骤 6（可选）：控制 schema 成本

Ardot 常驻约 16.5k tokens。如果觉得贵：

**6a. 只在需要的 preset 里暴露**（零代码）

在 `pruge-mode/guard.js:76` 的 deny-list 里加 Ardot，或反过来在别的 preset 里 allow-list 它：

```js
// 例：pruge preset 里隐藏 Ardot（省 16.5k）
const MCP_DENIED_PREFIXES = ['mcp__rea__', 'mcp__ardot-remote__']
```

改完要重启 DSH（guard 是启动时加载的模块，见 `ardot-mcp/README.md:100`）。

**6b. 写成预设作用域**（如果只有某个模式下需要设计功能）

把 Ardot 那行从 bundle 根条目移到某个 `@deepseek-ai/dsh-agent-preset` 的 `config.plugins:` 里，这样它只对该 preset 的 Agent 可见。参考 `pruge-mode/cordis.patch.yml:162-190` 的 REA 写法。

**6c. 写懒加载分发工具**（工作量最大，收益最大）

用导出的 `createMcpToolDefinition()` 自己注册 1 个 `ardot_call` 而不是 28 个工具。收益：16.5k → ~0.3k tokens。代价：模型要在 prompt 里知道工具清单，参数校验后移到服务端。

---

## 8. 关键风险清单

| 风险 | 影响 | 缓解 |
|---|---|---|
| `headers` 只在激活时求值 | token 过期后**静默**全部失败（工具还列着，调用报 401） | 用方案 B/C，token 每请求刷新 |
| refresh_token 会 rotate | 刷新后不落盘 → 下次刷新失败 | `ardot-auth.mjs` 和两个新实现都已处理 |
| 无任何自动刷新调度 | 15 天后必失效 | 方案 B/C |
| `reconnect.maxAttempts=10` 后**注销工具** | 连续失败 10 次后工具消失，只能靠重载恢复 | `failOnStartupError: false` + 保持 `reconnect.enabled` |
| 无 `mcp-session-id` | 写垫片时依赖 session 头会失败 | 已实测确认，两个实现都按需透传 |
| Ardot 会加工具（26→28） | schema 成本上涨，KV cache 前缀失效 | 用 §7 步骤 6 控制曝光面 |
| `createRemote` 会把 token 写进 `cordis.patch.yml` 明文 | 凭据进版本库 | 方案 B 后不再写 token，配置里只有文件路径 |
| `!!js` 的 `eval` 沙箱 | 配置能用 `eval` 跑任意代码，配置文件被改 = RCE | 别把 `cordis.patch.yml` 放进可写共享位置 |

---

## 9. 「未找到」项

诚实标注以下内容在调研范围内**没有找到**：

1. **`dsh-mcp-client` 的 `authProvider` 支持** —— 不存在，源码里没有任何传参路径。
2. **`toolPrefix` / 去掉 `mcp__` 前缀的配置** —— 全仓库零命中。
3. **插件自带的工具过滤 / 白名单 / 懒加载** —— `lib/index.js` 全文无相关代码或字段。
4. **`cordis.patch.yml` 的 `fs.watch` 热重载** —— `grep -rn "fs.watch\|watchFile\|chokidar"` 在 `dsh-app-boot`、`dsh`、`cordis-plugin-loader`、`cordis-plugin-include` 下**零命中**。配置改动需要经 `plugin_manager` 或重载才会生效；`dsh-plugin-manager` 的 `setPluginEnabled` 是显式 `reload()`。
5. **DSH 安装目录里的 `docs/`** —— README 里引用的 `../../../docs/config-catalog.md` 等路径在安装包中**不存在**（`ls app/dsh/node_modules/../../..` 只有 `dsh/lib/node_modules/package.json/renderer/runtime`）。所以 README 里的链接无法打开，只能靠源码验证。
6. **`@modelcontextprotocol/server` 包** —— 安装目录只有 `client` 和 `core`，**没有** server SDK。方案 B 因此手写了 stdio 帧处理（newline-delimited JSON）而不是用 SDK；这也让桥接脚本零依赖。

---

## 10. 附：证据文件清单

| 文件 | 用途 |
|---|---|
| `C:\Users\bx233\AppData\Local\Programs\DeepSeek Harness\resources\app\dsh\node_modules\@deepseek-ai\dsh-mcp-client\lib\index.js` | 主实现（836 行），所有行为结论的源头 |
| 同上 `README.md` / `README.zh.md` | 契约、字段表、限制 |
| 同上 `package.json` | 版本 `0.2.1-rc.5`，依赖 `@modelcontextprotocol/client@2.2.0` |
| `...\@deepseek-ai\cordis-plugin-loader\lib\index.js` | `!!js` 求值机制（232-244、607-612） |
| `...\@deepseek-ai\cordis-plugin-include\lib\index.js` | `insert` 补丁语义（68-84） |
| `...\@deepseek-ai\dsh-app-boot\lib\index.js` | 三层 patch 叠加顺序（1127-1133） |
| `...\@deepseek-ai\dsh-scope\lib\index.js` | `scopeOf()`（312-314） |
| `...\@deepseek-ai\dsh-agent-preset-registry\lib\index.js` | preset scope 建立（495-505） |
| `...\@deepseek-ai\dsh-tools\lib\index.js` / `README.md` | `restrict()`、`deferLoading` |
| `...\@deepseek-ai\dsh-llm-deepseek\lib\index.js` | `defer_loading` 下传（1731） |
| `...\@modelcontextprotocol\client\dist\index.mjs` | SDK `authProvider`（5047-5064）、`SSEClientTransport` |
| `C:\Users\bx233\.dsh\cordis.patch.yml` | 用户宿主级层（当前为空，含 REA 作用域说明） |
| `C:\Users\bx233\.dsh\profiles\desktop\package.json` | bundle 注册 |
| `C:\Users\bx233\Documents\deepseek-harness\default-workspace\ardot-mcp\cordis.patch.yml` | **Ardot 实际配置** |
| 同上 `ardot-auth.mjs` | OAuth 脚本（293 行，PKCE + 动态注册 + refresh） |
| 同上 `README.md` | 设计说明 + 踩坑记录 |
| `C:\Users\bx233\Documents\deepseek-harness\default-workspace\pruge-mode\cordis.patch.yml` | preset scope 写法范例 |
| 同上 `guard.js` / `pruge-mcp-guard/index.js` | `restrict()` 掩码范例 |
| `E:\better-zslf\reports\ardot-stdio-bridge.mjs` | **方案 B 实现（实测通过）** |
| `E:\better-zslf\reports\ardot-oauth-proxy.mjs` | **方案 C 实现（实测通过）** |
