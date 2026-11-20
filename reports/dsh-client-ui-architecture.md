# DSH 客户端 UI 插件架构报告

> 目标：摸清 DeepSeek Harness (DSH) 客户端 UI 插件架构，重点回答「右侧边栏 / 面板」与「iframe / webview 嵌入外部页面」两件事能做到什么程度。
>
> 调查时间：2026-11-08
> DSH 版本：`0.2.1-rc.5`
> 安装目录（只读）：`C:\Users\bx233\AppData\Local\Programs\DeepSeek Harness\resources\app\dsh\node_modules\@deepseek-ai\`
> 用户 profile：`C:\Users\bx233\.dsh\profiles\desktop\`（`DSH_PROFILE=desktop`，`DSH_HOME=C:\Users\bx233\.dsh`）
>
> 重要前提：本次调查的安装包**只带 `lib/*.js`（rolldown/tsdown 打包后的明文 bundle），不带任何 `.d.ts` 类型声明**。已确认：
> `find ... -name "*.d.ts"` 在 `dsh-client-ui-layout` / `dsh-client-ui-sidebar-right` / `dsh-client-ui-sidebar-browser` 三个包中**零命中**。所以本报告的证据全部来自 **bundle 源码 + 各包 README.md（随包发布的架构文档，信息密度极高）**，凡 README 断言我都尽量回到 `lib/client.js` 用代码行交叉验证。

---

## 0. 一页结论（TL;DR）

| 问题 | 结论 |
|---|---|
| 有 `ctx.layout.registerPanel({slot:'right'})` 这种 API 吗？ | **没有**。`ctx.layout` 只有 `selectPanel / toggleSidebar / openRightbar / closeRightbar / beginNavigation / panelInfo` 六个成员。右侧栏不走「注册 panel」模型，走的是**两层注册**：`ctx.sidebarRightTabs.register()` 声明 tab 类型 + `ctx.slots.register({name:'sidebar.right.pane.tab', key})` 声明 tab 主体。 |
| 右侧栏的 slot id 是什么？ | 布局层是 `rightbar`（root/single），其下 `rightbar.session`（session/single）。第三方真正要用的是 `sidebar.right.pane.tab`（session/keyed）+ `sidebar.right.pane.tab.title`（session/keyed）+ `sidebar.right.tab.menu.item`（session/list）+ guide 系列。 |
| 面板是 React 组件吗？ | 是。`slots.register(options, Component)`，Component 是普通函数组件，props 由框架注入（session props + `inject` 返回值 + 标准 hook）。 |
| 能只靠注册纯 HTML/iframe 显示外部网站吗？ | **能，而且这正是官方 sidebar-browser 包在做的事**：它注册一个 tab 类型（kind=`browser`），body 里 `document.createElement('iframe')`。你完全可以照抄一个「只嵌一个固定 URL」的极简版本，不用碰 `browser` 这个 kind。 |
| sidebar-browser 是 iframe 还是 webview？ | **两个都有，按运行时分流**：`globalThis.dshDesktop?.protocolVersion === 1` 有值 → Electron `<webview>`；否则 → `<iframe>`。Desktop 走 webview 且带主进程 lease 审批，Web 走 iframe。 |
| 支持任意 URL 吗？ | 支持任意 **HTTP/HTTPS**（含 loopback）。拒绝 `file:`、`javascript:`、`data:`、`blob:`、带 credentials 的 URL、**以及 DSH 自己的 origin**（`application-origin`）。 |
| 有 CSP / 白名单限制吗？ | **没有域名白名单**，也没有自定义 CSP。限制来自 iframe `sandbox` 属性（5 个 allow-* 标志，可在 UI 里逐 tab 关掉）+ 上游站点自己的 `X-Frame-Options`/CSP。**Ardot（ardot.tencent.com）实测没有返回 X-Frame-Options / CSP frame-ancestors，可被 iframe 嵌入。** |
| 双向通信（postMessage）？ | **sidebar-browser 完全没有暴露**（`grep postMessage` 命中 0 次）。但**你自己写的 iframe 面板可以完全自由地做 postMessage**——因为那个 iframe 元素是你创建的，`contentWindow` 在你手里。这是本报告最重要的可行性结论之一。 |
| 客户端插件能调主进程/后端吗？ | 后端：能，`ctx.remote`（Typert RPC over `/api`）或 `ctx.connection.rpc`。主进程：**只能用既有的 preload 白名单面（`window.dshDesktop`）**，第三方无法新增 IPC channel —— preload 里有一堆 origin/frame 校验。 |
| HMR？ | 有，`dsh-client-hmr`：host 端每 `500ms` stat-poll 每个 bundle，变了就 `clientModules.rebuilt(id)`，通过 `/plugins/events` SSE 推给浏览器，浏览器重跑 bundle 并 remount 插件（**React state 丢失，session/workspace 状态保留**）。 |
| 用户 plugins 目录 `~/.dsh/plugins/` 是插件加载路径吗？ | **不是**。实测那是**运行时数据目录**（`dsh-plugin-subscriptions` 往里写 `subscriptions/auth.json` 等）。真正的插件安装位是 `~/.dsh/profiles/desktop/node_modules/` + `package.json` 的 `dsh.profile.bundles` + `cordis.patch.yml`。 |

---

## 1. Slot 系统：`dsh-client-ui-layout` 提供了哪些 slot

### 1.1 slot 服务本身不在 layout 里

`ctx.slots` 是 `dsh-client-ui-renderer` 安装的 Cordis Service，不是 layout 提供的：

`dsh-client-ui-renderer/lib/client.js:1867-1875`
```js
function apply(ctx) {
    new SlotRegistry(ctx).install(createSlotRenderer());
    ctx.reflect.provide("uiRenderer", { mount: (container) => {
        const root = mountApp(container, buildRenderApp({ ctx }));
        return () => { root.unmount(); };
    } });
}
```

`SlotRegistry` 的类声明（`dsh-client-ui-renderer/lib/client.js:1305-1352`）：
```js
var SlotRegistry = class extends _deepseek_ai_cordis.Service {
    _core = new _deepseek_ai_dsh_client_ui_slots.SlotCore();
    ...
    constructor(ctx) {
        super(ctx, "slots");          // ← ctx 上的服务名就是 "slots"
        this._core.onMutate((key) => { ctx.emit("slots/changed", key); });
    }
```

注意 `require("@deepseek-ai/dsh-client-ui-slots")`（`client.js:14`）—— **`dsh-client-ui-slots` / `dsh-client-ui-dockkit` / `dsh-client-store` / `dsh-client-ui-primitives` 这四个包在磁盘上不存在**，它们只存在于 web shell 的静态模块表里：

`dsh-web-frontend/dist/assets/index-BXV-oxvX.js`（`OZ()` 函数）
```js
function OZ(){return{react:aC,"react/jsx-runtime":dC,"react-dom":gC,"react-dom/client":_C,
"@deepseek-ai/cordis":Ub,zod:Vj,"@tanstack/react-virtual":Xj,
"@deepseek-ai/dsh-client-store":xI,
"@deepseek-ai/dsh-client-ui-slots":jI,
"@deepseek-ai/dsh-client-ui-primitives":TU,
"@deepseek-ai/dsh-client-ui-dockkit":SZ}}
```
> **实务含义**：插件 bundle 里可以直接 `require("react")` / `require("@deepseek-ai/dsh-client-ui-primitives")` / `require("@deepseek-ai/dsh-client-ui-slots")`，它们是平台种子模块；但**不要**在自己的 `package.json` dependencies 里声明它们（会解析到不存在的包）。

### 1.2 `ctx.layout` 的真实 API（没有 registerPanel）

`dsh-client-ui-layout/lib/client.js:452-495`
```js
var LayoutController = class {
    panels; hasMainPanel; panelInfo;
    navigation = new AbortController();
    constructor(panels, hasMainPanel, panelInfo) { ... }
    /** Select a global panel or return to the Conversation. */
    selectPanel(panelId) {
        if (panelId !== null && !this.hasMainPanel(panelId))
            throw new Error(`layout.selectPanel: main panel "${panelId}" is not registered`);
        this.navigation.abort();
        this.panels.selectPanel(panelId);
    }
    beginNavigation() { ... }
    dispose() { this.navigation.abort(); }
    toggleSidebar() { this.panels.toggleSidebar(); }
    /** Report the right panel's track and fullscreen presentation. */
    openRightbar(track, fullscreen) { this.panels.openRightbar(track, fullscreen); }
    /** Report the right panel as hidden: no track, no handle. */
    closeRightbar() { this.panels.closeRightbar(); }
};
```

**验证不存在的 API**：
```
$ grep -rn "registerPanel" @deepseek-ai/*/lib/*.js      → 0 命中
$ grep -rn "layout\.register" @deepseek-ai/*/lib/client.js → 0 命中
```

可见 `openRightbar/closeRightbar` **不是「注册面板」，而是「上报占位几何」** —— 由 ui-sidebar-right 这个占位者自己调用，告诉 AppFrame「右侧轨道要多宽、是不是全屏」。第三方**不该、也不必**调它们。

`ctx.layout` 由 layout 包在同一个 `ctx.effect` 里 provide（`client.js:614-622`）：
```js
const layout = new LayoutController(instance.actions,
    (id) => ctx.slots.entries("main").some((entry) => entry.options.key === id),
    { getSnapshot: ..., subscribe: ... });
const disposePanelInfo = ctx.slots.provideRoot({ hooks: { panelInfo: layout.panelInfo } });
const disposeService = ctx.reflect.provide("layout", layout);
```

### 1.3 layout 声明的 root 子 slot 表（这就是「layout 提供的 slot 清单」）

`dsh-client-ui-layout/lib/client.js:623-653`
```js
const disposeRegistration = ctx.slots.register({
    name: "root",
    locale: "common",
    children: {
        "sidebar":        { kind: "single", scope: "root" },   // 左栏
        "main":           { kind: "keyed",  scope: "root" },   // 全局页面（plugin manager / conversation）
        "rightbar":       { kind: "single", scope: "root" },   // ★ 右侧栏占位者
        "shell.bottom":   { kind: "single", scope: "root" },
        "shell.overlay":  { kind: "list",   scope: "root" },
        "shell.leading":  { kind: "single", scope: "root" }
    },
    store
}, AppFrame);
```

layout 包注册的 shortcut：`sidebar.left.toggle`（Mod+B），`client.js:654-688`。

### 1.4 slot 的四种 kind 与语义（从 shell bundle 反推）

`dsh-web-frontend/dist/assets/index-BXV-oxvX.js` 的 `entriesOfSlot`：
```js
entriesOfSlot(e){
  const t=this.records.get(e);
  if(!t?.spec) return i1;
  const r=t.spec.kind;
  if(r==="chain") return t.entries;
  const i=[],n=new Set;
  for(const s of t.entries){
    if(this.abdicated.has(s)) continue;
    const l = r==="keyed" ? s.options.key : r==="list" ? s.options.id : void 0;
    n.has(l) || (n.add(l), i.push(s));      // ← 同 key/id 只取第一个（先注册者胜）
  }
  ...
}
```

对应的报错文案（同一文件）：
```
`single slot "${e.name}" already has a registration ${s(h)}`
`keyed slot "${e.name}" requires options.key`
`keyed slot "${e.name}" already has an entry for key "${e.key}" ${s(h)}`
`list slot "${e.name}" requires options.id`
`list slot "${e.name}" already has an entry with id "${e.id}" ${s(h)}`
`chain slot "${e.name}" requires options.select`
`slot "${e.name}" is not declared (a parent entry's children table must declare it)`
`slot "${h}" is already declared (by ${p.declaredBy??"an unknown entry"})`
```

| kind | 注册必需字段 | 数量 | 排序/覆盖 |
|---|---|---|---|
| `single` | — | 严格 1 个，第二个 `register` 抛错 | — |
| `keyed` | `key` | 每个 key 1 个 | 同 key 第二个抛错 |
| `list` | `id` | 多个 | 按 `order` 升序；同 id 第二个抛错 |
| `chain` | `select` | 多个 | 依次 `select(ownerProps)`，第一个返回非 null 者渲染 |

`keyed` / `list` 同 cell 重复时**第二个直接抛错**；renderer 侧另一套「`priority` + abdication」是给**渲染期崩溃隔离**用的（`dsh-client-ui-renderer/lib/client.js:1546-1556` 注释：*"Shadowing winners per cell for a key: the first live (non-abdicated) entry of each cell in priority order"*）。

### 1.5 「一个第三方插件怎么往右侧栏注册面板」

**没有 `ctx.layout.registerPanel`。** 官方规定路径是（`ui-sidebar-right/README.md:81`，原文）：

> 1. **The type** — `ctx.sidebarRightTabs.register({ id, kind, patterns?, priority?, canOpen?, title, guide?, keepMounted? })`, a static declaration with no runtime hook, returning a disposer.
> 2. **The body** — `ctx.slots.register({ name: 'sidebar.right.pane.tab', key: definition.id }, Body)` reads `{ sidebar, panel, tab }` through the framework-injected `useTabInfo()`.

这两个注册都必须包在调用方的 `ctx.effect(...)` 里，注册寿命 = 插件寿命。

**真实的第三方代码范例**（你机器上装的 `dsh-context`），它做的正是这件事：

`C:\Users\bx233\.dsh\profiles\desktop\node_modules\dsh-context\lib\client.js`
```js
let zd=`dsh-context`, Bd=`dsh-context`;
function Vd(e,t,n,r){
  let i=e.inject([`sidebarRightTabs`], e=>{
    let i=e, a=[], o=e=>{typeof e==`function`&&a.push(e)};
    try{
      let e=i.sidebarRightTabs;
      if(e===void 0||typeof e.register!=`function`) return;
      o(e.register({
        id: zd, kind: Bd,
        title: ()=>n(`tab.context`),
        guide: [{ order:20, title:()=>n(`tab.context`),
                  description:()=>n(`sidebar.guideDescription`), icon:w }]
      })),
      o(i.slots.inject(`sidebar.right.pane.tab`, ()=>i.slots.register(
          {name:`sidebar.right.pane.tab`, key: zd, locale: r},
          e=>t({...e, host:`sidebar`})))),
      o(i.slots.inject(`sidebar.right.pane.tab.title`, ()=>i.slots.register(
          {name:`sidebar.right.pane.tab.title`, key: zd}, E(n))))
    }catch{ for(let e of a) e(); return }
    return ()=>{ for(let e of a) e() }
  });
  return ()=>{ i?.dispose?.() }
}
```
注意几个可抄的细节：用 `ctx.inject(['sidebarRightTabs'], ...)` 而不是硬性 `inject` 数组（服务不在也能安全降级）；`slots.inject(slotName, cb)` 保证父 slot 已声明后再注册；`key` 用 `id`。

另一个第三方范例（打开 tab 的调用方）：
```js
function Qd(e){try{let t=H(e.get(`sidebarRight`));
  return t===null||typeof t.openTab!=`function`?!1:(t.openTab.call(t,Bd),!0)}catch{return!1}}
```
即 `ctx.sidebarRight.openTab(kind, options?)`。

### 1.6 slot body 的 props 从哪来（关键，决定你能拿到什么）

`dsh-client-ui-renderer/lib/client.js:792-810`
```js
function renderEntry(slotKey, Comp, kit, standard, injected, slotInjected, ownerProps, hookContext, hasHookContext) {
    if (slotInjected.slotHookFactories === void 0) return jsx(Comp, {
        ...kit, ...injected, ...slotInjected.props, ...ownerProps
    });
    return jsx(ContextualEntry, { slotKey, Comp, kit, standard, injected, slotInjected, ownerProps, hookContext, hasHookContext });
}
```
合并顺序：`kit`（框架 standard hook props）→ `injected`（你的 `inject()` 返回值）→ `slotInjected.props`（父 slot 声明里的 inject）→ `ownerProps`（渲染点上由父组件传的，**优先级最高**）。

`inject` 可以是函数，按 scope 求值（session 作用域会拿到 `(sessionId, actions)`，见 sidebar-browser 的用法 `client.js:1619`）。

---

## 2. sidebar-right 面板机制

### 2.1 现在右侧栏里有哪些 tab 类型（shipped）

| kind | id | band | multiple | 来源文件:行 |
|---|---|---|---|---|
| `guide` | `@deepseek-ai/dsh-client-ui-sidebar-right/guide` | `builtin` | — | `ui-sidebar-right/lib/client.js:4830, 4840-4847` |
| `browser` | `@deepseek-ai/dsh-client-ui-sidebar-browser` | `builtin` | `true` | `ui-sidebar-browser/lib/client.js:1430-1449` |
| `text` | `@deepseek-ai/dsh-client-ui-sidebar-documentpreview` | `fallback` | — | `ui-sidebar-documentpreview/lib/client.js:1053, 1082-1084` |
| `files` | `@deepseek-ai/dsh-client-ui-sidebar-files` | `builtin` | — | `ui-sidebar-files/lib/client.js:14, 25-26` |
| `terminal` | （同上模式） | `builtin` | `true` | `ui-sidebar-terminal/lib/client.js:440-442` |

`guide` 的完整 declaration 只有 5 个字段（`client.js:4840-4847`）：
```js
function guideDefinition(t) {
    return { id: GUIDE_ID, kind: GUIDE_KIND, priority: "builtin", title: () => t("tab.guide.title") };
}
```

`browser` 的（`client.js:1434-1450`）：
```js
function browserDefinition(t) {
    return {
        id: BROWSER_ID,
        kind: BROWSER_KIND,               // "browser"
        multiple: true,                   // ← 可开多个独立实例
        priority: "builtin",
        title: () => t("type.label"),
        guide: [{
            id: "new", commandId: "browser.new", order: 30,
            title: () => t("guide.title"),
            description: () => t("guide.description"),
            icon: _deepseek_ai_dsh_client_ui_primitives.GuideArtworkBrowser
        }]
    };
}
```

### 2.2 注册机制：两段式 + band 优先级

**注册表实现** `ui-sidebar-right/lib/client.js:4580-4611`：
```js
register(definition) {
    const { id, kind } = definition;
    const entries = definition.guide ?? [];
    if (new Set(entries.map((e) => e.id)).size !== entries.length)
        throw new Error(`sidebarRight: duplicate guide entry id in "${id}"`);
    const band = definition.priority ?? DEFAULT_BAND;
    if (this.ids.has(id)) throw new Error(`sidebarRight: tab type id "${id}" is already registered`);
    const held = this.kinds.get(kind);
    if (held !== void 0 && !coexists(held, band))
        throw new Error(`sidebarRight: tab kind "${kind}" is already registered (${held.inForce.band})`);
    ...
    const dispose = this.ctx.effect(() => { this.ids.add(id); const slot = this.enter(kind, entry); this.refresh(); ... },
        `sidebarRight.tabs.register(${JSON.stringify(id)})`);
    return () => { dispose(); };
}
```

**band 定义**（`client.js:4508-4522`）：
```js
const RANKS = { extension: 3, builtin: 2, fallback: 1 };
const DEFAULT_BAND = "extension";      // ← 第三方不写 priority 就是最高档
function coexists(slot, band) {
    return band !== "fallback" && slot.inForce.band !== "fallback"
        && slot.inForce.band !== band && slot.shadowed === void 0;
}
```
=> **第三方（`extension`）可以「顶掉」某个 shipped 的 `builtin` kind**，unregister 后 builtin 自动恢复。但不能顶 `fallback`，也不能同 band 顶同 band。

**服务注册点** `client.js:4965-4966`：
```js
const disposeRegistry = ctx.reflect.provide("sidebarRightTabs", tabs);
const disposeService = ctx.reflect.provide("sidebarRight", controller);
```

### 2.3 右侧栏的全部 slot（含第三方可用的扩展位）

`ui-sidebar-right/lib/client.js:5030-5078` 声明了 `rightbar` 与 `rightbar.session` 两层：

```js
const disposeSeat = ctx.slots.inject("rightbar", function* () {
    yield ctx.slots.register({
        name: "rightbar",
        children: { "rightbar.session": { kind: "single", scope: "session" } },
        inject: () => ({ hooks: { views: views.source }, mountView: (reference) => views.mount(reference) })
    }, RightbarRoot);
    yield ctx.slots.register({
        name: "rightbar.session",
        locale: NS,
        children: {
            "sidebar.right.pane.tab":       { kind: "keyed", scope: "session", inject: { hooks: { tabInfo: tabInfoFactory } } },
            "sidebar.right.pane.tab.title": { kind: "keyed", scope: "session", inject: { hooks: { tabInfo: tabInfoFactory } } },
            "sidebar.right.tab.menu.item":  { kind: "list",  scope: "session" }
        },
        store,
        inject: (sessionId) => ({ ...injected, measureRoom: ..., closeTab: ...,
            keyedHooks: { tabNavigation: ... }, occurrence: (tab) => controller.tabDomain.occurrence(sessionId, tab) })
    }, RightbarSeat);
});
```

guide body 自带三个子 slot（`client.js:5101-5122`）：
```js
const disposeGuide = ctx.slots.inject("sidebar.right.pane.tab", () => ctx.slots.register({
    name: "sidebar.right.pane.tab",
    key: GUIDE_ID,
    locale: NS,
    children: {
        "sidebar.right.tab.guide.actions": { kind: "list",  scope: "root" },
        "sidebar.right.tab.guide.entry":   { kind: "keyed", scope: "session", inject: {...} },
        "sidebar.right.tab.guide":         { kind: "chain", scope: "session", inject: {...} }
    },
    inject: () => guideInjected
}, GuideBody));
```

**完整 slot 清单（跨包 grep `"sidebar.right.*"`）**

| slot | kind | scope | 声明者 | 用途 |
|---|---|---|---|---|
| `rightbar` | single | root | ui-layout（children 声明）→ ui-sidebar-right 占用 | 右侧栏占位者（不建议第三方碰） |
| `rightbar.session` | single | session | ui-sidebar-right | 每 session 一个 docking surface |
| `sidebar.right.pane.tab` | **keyed** | session | ui-sidebar-right | ★ **tab 主体**，key = tab 类型 id |
| `sidebar.right.pane.tab.title` | **keyed** | session | ui-sidebar-right | ★ tab 标题（chip），key 同上 |
| `sidebar.right.tab.menu.item` | list | session | ui-sidebar-right | tab 右键菜单追加项 |
| `sidebar.right.tab.guide` | chain | session | ui-sidebar-right | 替换 guide body |
| `sidebar.right.tab.guide.entry` | keyed | session | ui-sidebar-right | 自定义 guide 卡片 |
| `sidebar.right.tab.guide.actions` | list | root | ui-sidebar-right | guide 的「More tools」菜单行 |
| `sidebar.right.tab.document` | keyed | session | documentpreview | 文档渲染器主体 |
| `sidebar.right.tab.document.actions` | list | session | documentpreview | 文档头追加控件 |
| `sidebar.right.tab.document.action` | — | session | documentpreview | 文档动作 |
| `sidebar.right.tab.document.unpreviewable` | list | session | documentpreview | 不可预览占位 |
| `sidebar.right.tab.document.office.pdf` | keyed | session | documentpreview | office→pdf |
| `sidebar.right.tab.files.actions` | list | session | files | 文件树行动作 |

> 注意 `"sidebar.right.toggle"` 只出现在 shortcut id 里（`client.js:182`），**不是 slot**。

### 2.4 Panel 是 React 组件吗？—— 是

`ui-sidebar-right/lib/client.js:1368-1404` 的 `TabSlot`：
```js
function TabSlot({ renderSlot, occurrence, useTabTypes, useTabNavigation, useStore, fullscreen, shortcuts,
                   active, retainTab, tab, seat, fallback }) {
    const { id, signal, tabActions } = occurrence(tab);
    const definition = useTabTypes((types) => types.find((d) => d.kind === tab.kind));
    const retained = seat === "sidebar.right.pane.tab" && definition?.keepMounted === true;
    useLayoutEffect(() => retained ? retainTab(tab.id, signal) : void 0, [...]);
    const hookContext = useMemo(() => ({
        tabId: tab.id, shortcuts, title: seat === "sidebar.right.pane.tab.title",
        fullscreen, active, signal, actions: tabActions, useStore, useTabNavigation
    }), [...]);
    const content = renderSlot(seat, {}, {
        entryKey: definition?.id ?? tab.kind, fallback, hookContext
    });
    return seat === "sidebar.right.pane.tab.title"
        ? jsx("span", { className: ..., "data-sidebar-right-tab": tab.id, "data-sidebar-right-occurrence": id, children: content }, id)
        : jsx("div",  { className: SidebarRight_module_css_default.tabBody, "data-sidebar-right-tab": tab.id, "data-sidebar-right-occurrence": id, children: content }, id);
}
```

body 容器 CSS 是 `flex-direction:column; height:100%; min-height:0; display:flex; overflow:hidden`（`_tabBody{...}`），所以 **子元素只要 `flex:auto; min-height:0` 就能撑满**——iframe 的 `bZwPEG_frame` / `bZwPEG_webview` 正是这么写的。

`useTabInfo()` 返回的结构（`client.js:4866-4888`）：
```js
return {
    sidebar: { expanded: layout.expanded, fullscreen },
    panel:   { id: pane.id },
    tab: { ...tab, visible: ..., navigation, signal, actions, refreshShortcut: shortcuts.find(r => r.id === "page.refresh") }
};
```

### 2.5 「能不能只靠注册一个纯 HTML/iframe 就显示外部网站」—— 能

三条路径，按侵入性从小到大：

**(a) 复用官方 browser tab（零代码）**
```js
ctx.sidebarRight.openTab('browser', { params: { url: 'https://ardot.tencent.com/...' } });
```
`ui-sidebar-browser/README.md:43` 明写：*"Client plugins can open a tab through `ctx.sidebarRight.openTab('browser', { params: { url } })`. The optional URL passes the same validation as address-bar input before navigation."*
代码侧证据（参数读取）：`ui-sidebar-browser/lib/client.js:141,148,156`
```js
const initialUrl = useRef(tab.navigation.params?.url);
const [draft, setDraft] = useDraft(target?.url ?? initialUrl.current, state?.addressRevision ?? 0);
... initialUrl: initialUrl.current,
```
=> `params.url` 透传到 `BrowserController.start(initialUrl)`（`client.js:484-487`）。
**限制**：这个 tab 是官方「浏览器」，带地址栏/前进后退/开系统浏览器等整套 chrome；你只能给它一个初始 URL，**不能注入脚本、不能监听页面消息**。

**(b) 自己注册一个 kind + iframe body（推荐，完全可控）**
就是 `browser` 那套做法的极简版，见 §7 的落地清单。

**(c) 往 `main` keyed slot 塞一个整页 iframe**
`ctx.layout.selectPanel(id)` 会把它当全局页面替换整个中央区（不是侧栏），`ui-plugin-manager` 就是这么占 `main` 的（`lib/client.js:4411-4423`）。**不是侧栏方案，列出仅供对照。**

---

## 3. sidebar-browser 到底做什么

### 3.1 双 carrier：iframe（Web） / `<webview>`（Desktop）

`ui-sidebar-browser/lib/client.js:1596-1633`（apply 尾部）：
```js
const carrier = globalThis.dshDesktop;
const desktop = carrier?.protocolVersion === 1 ? carrier.browser : void 0;
...
ctx.effect(() => ctx.sidebarRightTabs.register({
    ...browserDefinition(t),
    keepMounted: desktop !== void 0          // ← Desktop 才 keepMounted
}), "ui-sidebar-browser.type");
const installFrames = (scope, factory) => { ... };
if (desktop === void 0) installFrames(ctx, () => createIframePage);
else ctx.inject(["workspaces"], (scope) => {
    installFrames(scope, (sessionId) => (options) => createElectronPage(options, desktop, (signal) => browserWorkspace(scope.workspaces.list, sessionId, signal)));
});
```

`inject` 数组（`client.js:1537-1542`）：
```js
const inject = ["slots", "locale", "sidebarRight", "sidebarRightTabs"];
```

### 3.2 iframe 实现（逐行）

`ui-sidebar-browser/lib/client.js:919-985`
```js
/** Fixed iframe policy; top navigation and downloads are not granted directly. */
const WEB_BROWSER_SANDBOX = "allow-scripts allow-forms allow-same-origin allow-popups allow-popups-to-escape-sandbox";

var IframePresentation = class {
    mount(viewportId) {
        const host = document.getElementById(viewportId);
        if (host === null) throw new Error("iframe presentation: viewport is not mounted");
        ...
    }
    render() {
        const current = this.document; const host = this.host;
        if (current === void 0 || host === void 0) return;
        this.element?.remove();
        const element = document.createElement("iframe");
        element.className = Browser_module_css_default.frame;
        element.src = current.target.url;
        element.title = current.target.title;
        element.referrerPolicy = "no-referrer";
        element.dataset.sidebarBrowserFrame = "iframe";
        if (current.sandboxed) element.setAttribute("sandbox", WEB_BROWSER_SANDBOX);
        element.addEventListener("load", () => { if (this.element === element) this.events.loaded(current.revision); });
        element.addEventListener("error", () => { if (this.element === element) this.events.failed(current.revision); });
        this.element = element; this.rendered = true;
        host.append(element);
    }
};
```

要点：
- **没有 `allow` 属性**（`grep 'setAttribute("allow"'` → 0 命中），所以没有 package-owned Permissions Policy。
- `referrerPolicy = "no-referrer"`。
- `sandbox` 仅当 `current.sandboxed` 为真时加；UI 上可逐 tab 关掉（不持久化）。
- `data-sidebar-browser-frame="iframe"` 这个 marker 被 desktop preload 的快捷键校验用到（`resources/app/lib/preload-app.cjs:800`）。

### 3.3 `<webview>` 实现（Desktop）

`ui-sidebar-browser/lib/client.js:1335-1344`
```js
createElement(reservation) {
    const element = document.createElement("webview");
    element.className = Browser_module_css_default.webview;
    element.dataset.sidebarBrowserFrame = "webview";
    element.setAttribute("name", reservation.lease);
    element.setAttribute("partition", reservation.partition);
    element.setAttribute("allowpopups", "");
    element.setAttribute("src", "about:blank#" + reservation.lease);
    return element;
}
```
guest 是**先 `createGuest()` 向主进程申请 lease（`bridge.acquire(workspaceKey)`，`client.js:1161`），拿到 `lease` + `partition` 才能建标签**。主进程侧审批（`resources/app/lib/main.js:10592-10625`）：
```js
owner.on("will-attach-webview", (event, preferences, params) => {
    const id = typeof params.src === "string" && params.src.startsWith("about:blank#") ? params.src.slice(12) : "";
    const lease = this.leases.get(id);
    if (lease === void 0 || lease.owner !== owner || lease.attached || params.partition !== lease.partition) {
        event.preventDefault(); return;
    }
    lease.attached = true;
    for (const key of Object.keys(preferences)) if (key !== "disablePopups") Reflect.deleteProperty(preferences, key);
    Object.assign(preferences, {
        partition: lease.partition,
        nodeIntegration: false, nodeIntegrationInWorker: false, nodeIntegrationInSubFrames: false,
        contextIsolation: true, sandbox: true, webSecurity: true,
        allowRunningInsecureContent: false, webviewTag: false, plugins: false,
        navigateOnDragDrop: false, disableDialogs: true, devTools: !app.isPackaged
    });
    params.httpreferrer = "";
});
```
=> **`nodeIntegration:false` + `contextIsolation:true` + `sandbox:true`**，guest 拿不到 Electron/Node，也拿不到 `window.dshDesktop`（preload 只在 `dsh-app://app` 主 frame 注入，见 `preload-app.cjs:876`）。这正是 README 那句 *"The package never injects Electron or Node access into visited content."* 的代码依据。

主进程还额外封了：权限请求全拒（`main.js:10672-10676` `setPermissionRequestHandler(... callback(false))`）、`setWindowOpenHandler` 全 deny（改为开新的侧栏 tab）、`will-frame-navigate`/`will-redirect` 走 `allowedNavigation`（`main.js:10698-10704`：只允许 http/https、无 credentials、非应用 host）、guest 内再嵌 webview 直接 preventDefault。

主窗口 `webviewTag: primary`（`main.js:12009` 起的 `webPreferences` 块，第 **12015** 行 `webviewTag: primary`），即只有主窗口（`primary===true`）打开 webviewTag；其它窗口（如登录窗 `main.js:10211`、welcome 窗）是 `webviewTag: false`。

### 3.4 URL 策略：允许任意 HTTP(S)，拒绝其余

`ui-sidebar-browser/lib/client.js:363-407`
```js
function parseBrowserAddress(input, applicationOrigin) {
    const trimmed = input.trim();
    if (trimmed === "") return { ok: false, reason: "empty" };
    if (trimmed.length > 16384) return { ok: false, reason: "invalid" };
    const candidate = /^[A-Za-z][A-Za-z\d+.-]*:(?!\d+(?:[/?#]|$))/u.test(trimmed) ? trimmed : `https://${trimmed}`;
    let url;
    try { url = new URL(candidate); } catch { return { ok: false, reason: "invalid" }; }
    if (url.username !== "" || url.password !== "") return { ok: false, reason: "credentials" };
    if (url.protocol === "https:" || url.protocol === "http:") {
        if (applicationOrigin !== void 0 && applicationOrigin !== "null") try {
            if (url.origin === new URL(applicationOrigin).origin) return { ok: false, reason: "application-origin" };
        } catch {}
        return { ok: true, target: { kind: url.protocol === "https:" ? "https" : "http", url: url.href, title: url.hostname } };
    }
    return { ok: false, reason: "protocol" };
}
```

- **没有域名白名单**。任何 http/https 都行，含 `localhost` / `127.0.0.1`。
- 无 scheme 时补 `https://`。
- 拒绝：`file:`、`javascript:`、`data:`、`blob:`（都落到 `reason:"protocol"`）、带 credentials 的 URL、DSH 自身 origin、长度 > 16384。

### 3.5 CSP / 白名单：两侧都没有

- **DSH 侧无 CSP**：`dsh-web-frontend/dist/index.html` 里没有任何 `<meta http-equiv="Content-Security-Policy">`；`grep frame-src|frame-ancestors|default-src` 在 shell bundle → 0 命中；host 侧 `X-Frame-Options` / `frame-ancestors` 只有 `dsh-deepseek-account-platform/lib/index.js:1136` 那个**第三方登录页**自己设的 `frame-ancestors 'none'`，与后台 UI 无关。
- **上游站点侧**：这是唯一真实门槛。README 也承认（`ui-sidebar-browser/README.md:107`）：*"Browsers conceal many iframe failures for security: DNS, TLS, mixed-content, CSP, and `X-Frame-Options` failures may emit `load` or no actionable event instead of `error`."*

**Ardot 实测（本次调查现场验证）**：
```
$ curl -s -D - -o /dev/null -m 15 https://ardot.tencent.com/
HTTP/1.1 200 OK            ← 无 x-frame-options，无 content-security-policy
$ curl -s -D - -o /dev/null -m 15 https://ardot.tencent.com/file/693499159567438
HTTP/1.1 200 OK            ← 同上
```
响应头里只有 `Access-Control-Allow-Origin: *`、`Cache-Control`、`Server: APISIX` 等，**没有 `X-Frame-Options`，没有 `frame-ancestors`** ⇒ **Ardot 可被 iframe 嵌入**。

> ⚠️ 一个必须提醒的技术坑：Ardot 用 `set-cookie: i18nextLng=...`，且是 Next.js 应用。若 Ardot 的登录态依赖 **SameSite=Lax/Strict 的 cookie**，在 iframe 里 `allow-same-origin` 时是**第三方上下文**，Chromium 默认会拦；Ardot 若走 OAuth 跳转，跳出 iframe → 回来 cookie 写入也可能被拦。这是浏览器行为，DSH 改不了。（这一点是**基于常见行为的推断，未在本机实测 Ardot 登录**，标注为待验证。）

---

## 4. 客户端插件怎么写

### 4.1 最小结构

一个 DSH client plugin = **一个 npm 包**，同时可以带 host 半边。

**`package.json` 必需点**（对照 `ui-sidebar-browser/package.json` 实测输出）：
```json
{
  "name": "@deepseek-ai/dsh-client-ui-sidebar-browser",
  "type": "module",
  "main": "lib/index.js",
  "exports": {
    ".":        { "types": "./lib/types/index.d.ts", "default": "./lib/index.js" },
    "./client": { "types": "./lib/types/client/index.d.ts", "default": "./lib/client.js" }
  },
  "dsh": {
    "client": {
      "platform": "web",
      "inject": ["@deepseek-ai/dsh-api-workspace-controller",
                 "@deepseek-ai/dsh-client-ui-sidebar-right",
                 "@deepseek-ai/dsh-client-ui-session"]
    }
  }
}
```

字段校验源码（`dsh-client-modules/lib/index.js:62-76`）：
```js
function parseDshClient(pkgName, value) {
    if (value === void 0) return void 0;
    if (typeof value !== "object" || value === null) throw new Error(`client-modules: ${pkgName} has a non-object dsh.client declaration`);
    const decl = value;
    if (typeof decl.platform !== "string") throw new Error(`client-modules: ${pkgName} dsh.client.platform must be a string`);
    const inject = optionalStringArray(pkgName, "dsh.client.inject", decl.inject);
    const external = optionalStringArray(pkgName, "dsh.client.external", decl.external);
    if (decl.immediately !== void 0 && typeof decl.immediately !== "boolean") throw new Error(...);
    return { platform: decl.platform, ...inject !== void 0 ? { inject } : {}, ...external !== void 0 ? { external } : {}, ... };
}
```
`platform !== 'web'` 直接跳过（`lib/index.js:733`）。

`exports["./client"]` 解析规则（`lib/index.js:172-182`）：必须是字符串，或带字符串 `default` 的对象：
```js
function clientExportOf(pkgName, exportsField) {
    if (typeof exportsField !== "object" || exportsField === null) return void 0;
    const client = exportsField["./client"];
    if (client === void 0) return void 0;
    if (typeof client === "string") return client;
    if (typeof client === "object" && client !== null) {
        const fallback = client.default;
        if (typeof fallback === "string") return fallback;
    }
    throw new Error(`client-modules: ${pkgName} exports["./client"] must be a string or an object with a string default`);
}
```
找不到就抛：`client-modules: ${packageName} declares dsh.client but exports no "./client" bundle`（`lib/index.js:738`）。

### 4.2 入口 bundle 的**物理格式**（必须严格遵守）

**不是普通 ESM**，是 `window.__ModuleLoader__.load({id, factory})` 包起来的 lazy-CJS。真实样本 `dsh-comfyui/client/client.js:1-9` / 尾：
```js
window.__ModuleLoader__.load({
    id: "dsh-comfyui",
    factory: (require) => {
        var module = { exports: {} };
        var exports = module.exports;
        Object.defineProperty(exports, Symbol.toStringTag, { value: "Module" });
        let react = require("react");
        ...
        exports.apply = apply;
        exports.inject = inject;
        exports.name = name;
        return module.exports;
    }
});
//# sourceMappingURL=client.js.map
```
校验点在 `dsh-client-modules/lib/client.js:758,874`：
```js
failures.push(`${url}: loaded without registering "${id}" via __ModuleLoader__.load`);
...
if (!this.factories.has(id)) throw new Error(`client-modules: bundle ${url} loaded without registering "${id}" via __ModuleLoader__.load`);
```

**导出**：Cordis 插件惯例三件套 —— `name`（字符串）、`inject`（string[]）、`apply(ctx)`。可参考最小真实样本 `dsh-comfyui/client/client.js:5195-5247`：
```js
const name = "dsh-comfyui";
const inject = ["slots"];
function apply(ctx) {
    const t = makeT(getLang());
    ctx.effect(() => injectStyles(), "dsh-comfyui: styles");
    ctx.slots.inject("tool.call.toolview", () => ctx.slots.register({ name: "tool.call.toolview", key: "comfyui_run" }, (props) => ...));
    ctx.slots.inject("settings.section", () => ctx.slots.register({ name: "settings.section", id: "comfyui", order: 30, label: () => t("settingsTitle") }, ...));
    ...
}
exports.apply = apply; exports.inject = inject; exports.name = name;
```

`dsh.client.inject`（manifest 里）与模块里的 `inject`（Cordis 服务依赖）是**两个不同东西**：
- manifest `inject` → **bundle 加载顺序**（`lib/client.js:789-792`：`row.inject` 里的包先 arrive）。
- 模块 `inject` → Cordis **服务依赖**（等 `ctx.slots` 等就绪才 apply）。

### 4.3 怎么让 DSH 加载它

**真正的加载链路（容易踩坑，务必按这个来）**：

```
~/.dsh/profiles/<name>/package.json
   ├─ dependencies: { "@local/my-plugin": "link:C:/path/to/my-plugin" }
   └─ dsh.profile.bundles: [ ..., "my-plugin-bundle" ]
        ↓  (每个 bundle 的 dsh.bundle.patch 指向自己的 cordis.patch.yml)
my-plugin/cordis.patch.yml
   └─ - insert:
        - id: my-plugin
          name: 'my-plugin'
        ↓
~/.dsh/profiles/<name>/cordis.patch.yml   ← 你手写的 profile 补丁层（可选但常用）
~/.dsh/cordis.patch.yml                    ← home 级补丁层，优先级最高
```

**关键点：`~/.dsh/cordis.patch.yml` 不是「加一行就能加载插件」的注册表**，它是 **patch 层**（插入/覆盖 loader 条目）。而且**它现在被清空了**（`C:\Users\bx233\.dsh\cordis.patch.yml` 全文 13 行，`[]` 结尾，注释说明 REA 已迁到 preset 作用域）。空文件或只有注释会**导致启动失败**，必须写 `[]`：

`C:\Users\bx233\.dsh\cordis.patch.yml:13`
```yaml
[]
```
（`dsh-app-boot/README.md:61`：*"An empty or comments-only file fails boot — disable the layer with `[]` instead."*）

**bundle patch 的最小写法**（`dsh-comfyui/cordis.patch.yml` 全文）：
```yaml
# dsh-comfyui bundle patch: inserts the plugin into a profile's layer stack.
- insert:
    - id: comfyui
      name: 'dsh-comfyui'
```

**shipped row 的写法**（`dsh-web-app/cordis.patch.yml:295-297`，含条件 disable 语法）：
```yaml
    # Web profiles opt in; Desktop retains sandboxed HTTP(S) Browser tabs.
    - id: ui-sidebar-browser
      name: '@deepseek-ai/dsh-client-ui-sidebar-browser'
      disabled: !!js "ctx.get('profileContext')?.name !== 'desktop'"
```
=> **顺手解释了一个现象**：你 `~/.dsh/profiles/desktop/cordis.patch.yml` 里 grep 不到 `ui-sidebar-browser`，因为 desktop profile 下 `disabled` 求值为 `false`，走 shipped 默认开启，不需要 override。

**包名基准**：`~/.dsh/profiles/desktop/package.json` 里已有 `"@local/ardot-mcp": "link:C:/Users/bx233/Documents/deepseek-harness/default-workspace/ardot-mcp"` 等 6 个 `link:` 本地包，说明 **`link:` 到本地目录是这台机器上已验证可行的开发姿势**。

**`~/.dsh/plugins/` 不是代码目录**（重要澄清）：
```
$ ls ~/.dsh/plugins/
subscriptions/
$ ls ~/.dsh/plugins/subscriptions/
auth.json  images/  models.json
```
证据：`dsh-plugin-subscriptions/lib/http.js:8` —— *"The config lives at `~/.dsh/plugins/subscriptions/proxy.json`"*；`lib/model-defaults.js:5` —— *"The file lives at `~/.dsh/plugins/subscriptions/model-defaults.json`"*。**这是插件的数据目录，不是插件搜索路径。**

### 4.4 HMR

`dsh-client-hmr` 是**真的**，但**有前置条件**。

**Host 侧**（`dsh-client-hmr/lib/index.js:1-13, 46-63`）：
```js
import { statSync } from "node:fs";
/** System SSE endpoint pushing graph/rebuilt frames (wire protocol constant). */
const Config = z.object({ pollIntervalMs: z.number().step(1).min(1).default(500) });
const pollIntervalMs = config.pollIntervalMs;
const watched = new Map();
const publish = (id, watch, current) => {
    ctx.clientModules.rebuilt(id);       // ← 通知 composition 重算这个 row 的 rev
    ...
    watch.mtimeMs = current.mtimeMs; watch.ctimeMs = current.ctimeMs; watch.size = current.size;
};
```
即：**每 500ms stat-poll 每个 `lib/client.js`**（mtime + ctime + size），变了就 `rebuilt(id)`，通过 `/plugins/events` SSE 推给页面。

**Browser 侧**（`dsh-client-modules/lib/client.js:492-505`）：
```js
async replace(entry, id, rev, generation) {
    this.index.invalidateForReplacement(id, rev);
    await this.modules.prefetch(id);
    if (!this.current(generation)) return;
    await tearDownEntryFiber(entry);
    removeOwnedStyles(id);
    await this.modules.import(id, "", {});
    await entry.refresh();
    await entry.fiber?.await();
    if (entry.fiber === void 0) throw new Error(`client-modules: ${id} import failed (see console)`);
    this.revisions.set(id, rev);
}
```
=> 完整语义 = **tearDown 旧 fiber → 清它注入的 `<style>` → 重新 import → refresh → 等新 fiber 激活**。README 明确代价（`ui-sidebar-browser` 同级 `dsh-client-hmr/README.md`）：*"React state inside the reloaded plugin is lost, while session, workspace, and connection state survives."*

**前置条件**：
1. profile 必须挂 `dsh-hmr`（`dsh-base/cordis.patch.yml:28-32`：`- id: hmr / name: '@deepseek-ai/dsh-hmr' / disabled: !!js "!ctx.get('profileContext')" / config: { root: [] }`）。
2. 想看**代码改动**热更，还需跑 watcher：`pnpm run dev:web`；`--no-serve` 只挂 watcher。
3. 你当前 `~/.dsh/profiles/desktop/cordis.patch.yml` 里没有 hmr 行 ⇒ 用 base 默认（有 profileContext 时启用）。但**没有 watcher 进程时，只有 `client.js` 的 mtime/size 变化才会被 500ms poll 捕到**——也就是「手工改 bundle 文件 → 500ms 内自动换」。这其实对你手写 bundle 的调试很友好。

---

## 5. IPC / bridge

### 5.1 客户端 → 后端（推荐路径：`ctx.remote`）

架构：`dsh-api-gateway` 的 Client 半边提供 `ctx.remote`（`ClientRemote`），底层走 `ctx.connection.rpc.call('/api', endpoint, ...)`，传输是 HTTP POST（unary）/ WebSocket（`/api/remote.mux`，stream）。

`dsh-api-gateway/README.md:52-60`（原文节选）：
> `ctx.remote.$mount()` validates and registers a generated Host-for-Client contribution, then installs concrete direct and scoped methods for the calling Cordis fiber.
> Each unary call checks positional arity, constructs the descriptor's exact named `args`, and sends the typed values through `ctx.connection.rpc.call('/api', endpoint, ...)`.
> Every unary call resolves to `RemoteResult<T>` — `{ ok: true, value }` or `{ ok: false, error }` — and never rejects for a carrier problem.

代码：`dsh-api-gateway/lib/client.browser.js:1669` `async $mount(contribution) { ... }`。

**真实第三方用法**（`dsh-ops/lib/client.js:392-394`）：
```js
exports.inject = ['slots', 'locale', 'remote'];
async function apply(ctx) {
    ...
    await ctx.remote.$mount(runtime_contract_js_1.RUNTIME_REMOTE);
    ...
    ctx.slots.inject('plugins.row.config', function* () {
        yield ctx.slots.register({ name: 'plugins.row.config', key: 'dsh-ops#...', locale: 'dshOps' },
            (props) => ... jsx(ShellForm, { ...props, api: ctx.remote.opsSettings }));
    });
}
```
**descriptor 是纯 JS 对象，可以手写**（`dsh-ops/lib/settings/runtime-contract.js`）：
```js
const codec = (name, parse) => ({ mode: 'strict', typeSymbol: `dsh-ops#${name}`, create: () => ({ parse }) });
const jsonResult = codec('RuntimeResult', value => value);
function method(name, parameter, cancellable = false) {
    return { id: `dsh-ops#opsSettings/${name}`, service: 'opsSettings', namespace: 'opsSettings', method: name,
        invocation: { kind: 'direct' }, result: jsonResult,
        parameters: parameter ? [{ name: parameter.wire, wire: parameter.wire, source: 'json', codec: parameter.codec }] : [],
        ...(cancellable ? { cancellation: { parameter: 'signal' } } : {}) };
}
export const RUNTIME_DESCRIPTORS = Object.freeze([
    method('snapshot'),
    method('plan', { wire: 'action', codec: codec('RuntimeAction', parseAction) }),
    method('select', { wire: 'source', codec: codec('BashSource', parseSource) }),
    method('execute', { wire: 'id', codec: codec('RuntimePlanId', planId) }, true),
]);
export const RUNTIME_REMOTE = Object.freeze({ package: 'dsh-ops', descriptors: RUNTIME_DESCRIPTORS });
```
=> **不需要 codegen**，手写 descriptor + Host 侧 `ctx.typertGateway` 对应 Service 即可。

**更省事的替代**：如果只需要「客户端拿点数据」，很多第三方直接走 **HTTP 自定义路由**。`dsh-comfyui` 就是这么干的（`lib/index.js:373-388`）：
```js
ctx.inject(['webServer'], (webCtx) => {
    const webServer = webCtx.get('webServer');
    if (webServer !== undefined) {
        disposers.push(webServer.tapIndex((html) => ...));
    }
```
`dsh-host-webserver` 的路由 API（`README.md:59-67`）：
> `register(route)` adds a named `exact` or `prefix` HTTP route, `registerUpgrade(route)` adds an upgrade route for an exact pathname, and both return a disposer... `registerFallback(handler)` claims the one handler for every request no named route matches.

⚠️ **注意**：`/api` 这条路由是 `dsh-client-connection` 独占的（`dsh-client-connection/README.md`：*"When a Web carrier is present it also owns the sole `/api` route"*），你的自定义路由请用别的 prefix。

### 5.2 客户端 → 主进程（Electron）

**可以，但只能走既有白名单，不能加新 channel。**

`window.dshDesktop` 由 preload 注入，**且只在 `dsh-app://app` 的主 frame 里注入**（`preload-app.cjs:876`）：
```js
electron.contextBridge.exposeInMainWorld("dshDesktop",
    location.protocol === `dsh-app:` && location.hostname === "app" && process.isMainFrame
        ? createProductApi() : { protocolVersion: 1 });
```
（注意 else 分支：`{ protocolVersion: 1 }` —— 只有版本号，这就是 sidebar-browser 里 `carrier?.protocolVersion === 1 ? carrier.browser : void 0` 需要再判 `carrier.browser` 存在的原因。）

暴露的面（`preload-app.cjs:789-830`）：
```js
function createProductApi() {
    return {
        protocolVersion: 1,
        browser: createDesktopBrowserBridge(),     // { acquire, release, onOpenRequested }
        deviceInfo: () => ipcRenderer.invoke(DESKTOP_IPC.deviceInfo),
        keyboard: { closeWindow: ..., subscribe: ... },
        shortcuts: { get, edit, recording, subscribe },
        updates: { ... },
    };
}
```
`browser` bridge 就三个方法（`preload-app.cjs:757-785`）：
```js
function createDesktopBrowserBridge() {
    const listeners = new Map();
    ipcRenderer.on(DESKTOP_IPC.browserOpenRequested, (_event, request) => { ... });
    return {
        acquire: (workspace) => ipcRenderer.invoke(DESKTOP_IPC.browserAcquire, workspace),
        release: (lease) => ipcRenderer.invoke(DESKTOP_IPC.browserRelease, lease),
        onOpenRequested(lease, listener) { ... },
    };
}
```

**主进程侧 handler 在哪定义**：`resources/app/lib/main.js`（打包后的 Electron main），IPC 常量区如 `DESKTOP_IPC.browserAcquire` / `browserRelease` / `browserOpenRequested` / `deviceInfo` / `shortcutsGet` 等。校验极严，例如（`main.js:12521`）：
```js
if (owner === void 0 || event.sender !== owner.webContents || event.senderFrame !== owner.webContents.mainFrame
    || !event.senderFrame.url.startsWith(`dsh-app://app/`)) throw new Error("Rejected Platform command");
```
=> **第三方插件：宿主 side 无扩展点。** 想要新的主进程能力，只能改 `resources/app/lib/main.js` + `preload-app.cjs`（属于改安装目录，不推荐、且升级即失效）。

### 5.3 客户端 → 后端（底层逃生口）

`ctx.connection`（`dsh-client-connection`）提供 generation 管理、`rpc.open`、`fetch` 精确路由、`/api` 桥。对普通插件不必要；`ctx.remote` 已足够。

---

## 6. webview / iframe 的桥接能力

### 6.1 sidebar-browser 自己：**零桥接**

```
$ grep -n "postMessage\|MessageChannel\|MessagePort\|contentWindow" ui-sidebar-browser/lib/client.js
(0 命中)
$ grep -c "postMessage" ui-sidebar-browser/lib/client.js
0
$ grep -rn "executeJavaScript\|insertCSS" @deepseek-ai/*/lib/*.js
(0 命中)
```
全仓唯一的 `postMessage` 在 `dsh-client-file-upload/lib/client.js`（Web Worker 通信）与 `dsh-experimental-inspector`（DevTools 协议），与侧栏浏览器无关。

**结论：sidebar-browser 是一个纯「展示」carrier —— 单向、只读、不暴露任何页面内 API。** 它连 iframe 的 `contentWindow` 都只用来 append，不 keep 引用（`client.js:968-984`），因为它必须同时兼容 webview（webview 没有 `contentWindow`）。

它唯一暴露的「外界 → 页面」通道是 **导航**：
- `ctx.sidebarRight.openTab('browser', { params: { url } })` —— 开新 tab 到某 URL。
- 工具栏 Back/Forward/Reload/Go。
- `browser.new` 命令（Desktop Ctrl/Cmd+T）。
- Desktop：`bridge.onOpenRequested(lease, cb)` 把页面里的 `window.open` 请求转成「新开一个侧栏 tab」（`client.js:1171`；主进程 `main.js:10636-10645`）。

它**没有**：注入脚本、evaluate JS、监听页面 message、读页面 DOM/URL（跨域后连 URL 都读不到，README:106 承认这点）。

### 6.2 但你自己写的 iframe 面板：**完全自由**

这是本报告最关键的可行性判断。**因为 iframe 元素是你 `document.createElement('iframe')` 出来的、`src` 是你的 URL、append 到你自己的容器里 —— `contentWindow` 就在你手里**，`postMessage` 双向随便用，没有任何框架层拦截。

框架只做两件事，都不妨碍你：
1. 给你一个 body 容器 `<div class="...tabBody" data-sidebar-right-tab=...>`，CSS 是 `flex:column; height:100%; min-height:0; overflow:hidden`（`ui-sidebar-right/lib/client.js` 内 `_tabBody{...}`）。你 `flex:auto; min-height:0` 就能填满。
2. `tab.visible` / `tab.signal`（AbortSignal）告诉你何时可见/何时该清场（`client.js:4872-4880`）。

官方 `documentpreview` 的 HTML 预览就是这个思路（用 Blob URL + iframe），只是它追求「静态无脚本」所以自己加了 CSP（`ui-sidebar-documentpreview/lib/client.js:4243-4246`）：
```js
policy.setAttribute("http-equiv", "Content-Security-Policy");
policy.setAttribute("content", "default-src 'none'; script-src 'none'; style-src 'unsafe-inline'; img-src data:; font-src data:; media-src: data:; connect-src 'none'; frame-src 'none'; form-action 'none'; base-uri 'none'");
```
**你可以完全不加这个 CSP。**

### 6.3 跨域现实检查（Ardot 场景）

你嵌入 `https://ardot.tencent.com/...` 时：

| 能力 | 可行性 |
|---|---|
| iframe 显示 Ardot | ✅（实测无 X-Frame-Options / CSP） |
| 父页面读 Ardot DOM | ❌ 跨域，永远不行 |
| 父页面读 Ardot URL（导航后） | ❌ 跨域，浏览器遮蔽（sidebar-browser 的 address bar 就因此有 "unknown" 状态，`README:65,106`） |
| `iframe.contentWindow.postMessage(...)` → Ardot | ✅ 发送没问题；**但 Ardot 必须自己 `addEventListener('message')` 才有效果** —— 你无法强加 |
| Ardot → 父页面 postMessage | ✅ 只要 Ardot 主动发；父页面 `addEventListener('message')` + 校验 `event.origin` |
| 注入脚本进 Ardot | ❌ 跨域 iframe 无法注入（除非用 Desktop 的 webview + `executeJavaScript`，而 DSH **没有**暴露这个能力） |
| 用 Ardot 的 OAuth 登录态 | ⚠️ 第三方 cookie 策略可能拦；需实测（见 §3.5 末尾标注） |

> **务实建议**：如果只需要「在 DSH 里看着 Ardot」，iframe 方案 100% 可行，今天就能做。
> 如果需要「DSH 的 agent 操作 Ardot 画布」，**不要指望 iframe 桥接** —— 走 MCP（你的 profile 里已有 `@local/ardot-mcp`）或 Ardot 自己的 HTTP API，让 agent 在服务端调，UI 只负责显示。

---

## 7. 落地清单：在右侧栏嵌一个 Ardot 画布面板

### 7.1 推荐方案（方案 B：自定义 kind + iframe，完全可控）

> 为什么不用方案 A（`openTab('browser', {params:{url}})`）：它零代码、能立刻验证 Ardot 是否可嵌，但你拿不到 `contentWindow`，无法 postMessage。**建议先用 A 花 5 分钟验证「Ardot 在你的 DSH 里能显示」，再做 B。**

#### 需要写的文件

```
C:\Users\bx233\Documents\deepseek-harness\default-workspace\dsh-ardot-panel\
├── package.json          ← 包声明 + dsh.client
├── cordis.patch.yml      ← bundle patch（插入自己的 row）
├── index.js              ← host 半边（空实现即可）
├── client.js             ← ★ 浏览器半边（手写 __ModuleLoader__ bundle）
└── locale\
    ├── zh.json
    └── en.json
```

**(1) `package.json`**
```json
{
  "name": "@local/dsh-ardot-panel",
  "version": "1.0.0",
  "private": true,
  "type": "module",
  "main": "index.js",
  "exports": {
    ".": "./index.js",
    "./client": "./client.js",
    "./cordis.patch.yml": "./cordis.patch.yml",
    "./package.json": "./package.json"
  },
  "dsh": {
    "client": {
      "platform": "web",
      "inject": [
        "@deepseek-ai/dsh-client-ui-sidebar-right"
      ]
    },
    "bundle": {
      "patch": "./cordis.patch.yml"
    }
  }
}
```
> `dsh.client.inject` 里只需要列**加载顺序依赖**（sidebar-right 必须先加载）。服务依赖写在模块的 `inject` 数组里。

**(2) `cordis.patch.yml`**
```yaml
- insert:
    - id: ardot-panel
      name: '@local/dsh-ardot-panel'
```

**(3) `index.js`**（host 半边，可为空）
```js
export const name = 'dsh-ardot-panel'
export function apply() {}
```

**(4) `client.js`** —— 核心，约 120 行

```js
window.__ModuleLoader__.load({
  id: "@local/dsh-ardot-panel",
  factory: (require) => {
    var module = { exports: {} };
    var exports = module.exports;
    Object.defineProperty(exports, Symbol.toStringTag, { value: "Module" });

    const react = require("react");

    const ID   = "@local/dsh-ardot-panel";
    const KIND = "ardot";
    const ARDOT_ORIGIN = "https://ardot.tencent.com";

    // ---- React body -------------------------------------------------
    // props: useTabInfo (framework hook), t (locale), 以及你自己的注入
    function ArdotBody({ useTabInfo }) {
      const { tab } = useTabInfo();
      const url = tab.navigation.params?.url ?? ARDOT_ORIGIN + "/";
      const frameRef  = react.useRef(null);
      const [ready, setReady] = react.useState(false);

      // 父 → 子：等着 Ardot 报 ready，再往下推数据
      react.useEffect(() => {
        const onMessage = (event) => {
          if (event.origin !== ARDOT_ORIGIN) return;          // ★ 必须校验 origin
          const data = event.data;
          if (!data || data.source !== "ardot") return;
          if (data.type === "ready") setReady(true);
          // ... 处理 Ardot 主动推来的事件
        };
        window.addEventListener("message", onMessage);
        return () => window.removeEventListener("message", onMessage);
      }, []);

      // 子 → 父：把选中项之类推给 Ardot
      const sendToArdot = react.useCallback((payload) => {
        const win = frameRef.current?.contentWindow;
        if (!win) return;
        win.postMessage({ source: "dsh", ...payload }, ARDOT_ORIGIN); // ★ 必须指定 targetOrigin
      }, []);

      return react.createElement("div", {
          style: { display: "flex", flexDirection: "column", flex: "auto", minHeight: 0 }
        },
        // 可选：一条自己的小工具条
        react.createElement("div", {
            style: { flex: "none", display: "flex", gap: 6, padding: "4px 6px",
                     borderBottom: "0.5px solid var(--dsw-alias-border-l3)" }
          },
          react.createElement("button", {
              type: "button",
              onClick: () => sendToArdot({ type: "reload" }),
              disabled: !ready
            }, ready ? "Reload canvas" : "Connecting…")
        ),
        react.createElement("iframe", {
          ref: frameRef,
          src: url,
          title: "Ardot",
          referrerPolicy: "no-referrer",
          // 按需收紧：若 Ardot 不需要同源 cookie，去掉 allow-same-origin 更安全
          sandbox: "allow-scripts allow-forms allow-same-origin allow-popups allow-popups-to-escape-sandbox",
          style: { flex: "auto", width: "100%", minHeight: 0, border: 0,
                   background: "var(--dsw-alias-bg-base)" }
        })
      );
    }

    // ---- tab 标题（可选）--------------------------------------------
    function ArdotTitle({ useTabInfo, t }) {
      const { tab } = useTabInfo();
      return react.createElement(react.Fragment, null,
        react.createElement("span", { style: { marginRight: 4 } }, "◆"),
        tab.title);
    }

    // ---- 插件 -------------------------------------------------------
    const name   = "dsh-ardot-panel";
    // 服务依赖：slots（注册 seat）+ sidebarRightTabs（声明 kind）+ sidebarRight（openTab）
    // 三个服务都由 @deepseek-ai/dsh-client-ui-sidebar-right 提供（client.js:4965-4966）
    const inject = ["slots", "sidebarRightTabs", "sidebarRight"];

    function apply(ctx) {
      // ① 声明 tab 类型（不写 priority 就是 "extension" 最高档）
      ctx.effect(() => ctx.sidebarRightTabs.register({
        id: ID,
        kind: KIND,
        multiple: false,                    // 画布通常一个就够；要多个改 true
        title: () => "Ardot",
        guide: [{                           // ← 让它出现在右侧栏的引导页里
          id: "open",
          order: 40,
          title: () => "Ardot 画布",
          description: () => "在侧栏嵌入 Ardot 画布"
        }]
      }), "ardot-panel: tab type");

      // ② tab 主体
      ctx.effect(() => ctx.slots.inject("sidebar.right.pane.tab", () =>
        ctx.slots.register({ name: "sidebar.right.pane.tab", key: ID }, ArdotBody)
      ), "ardot-panel: body");

      // ③ tab 标题
      ctx.effect(() => ctx.slots.inject("sidebar.right.pane.tab.title", () =>
        ctx.slots.register({ name: "sidebar.right.pane.tab.title", key: ID }, ArdotTitle)
      ), "ardot-panel: title");

      // ④ 便捷入口：对话 header 右上角放个按钮
      ctx.effect(() => ctx.slots.inject("conversation.session.header.actions", () =>
        ctx.slots.register({
          name: "conversation.session.header.actions",
          id: "ardot-open",
          order: 200,
          label: () => "Ardot"
        }, () => react.createElement("button", {
          type: "button",
          onClick: () => ctx.sidebarRight.openTab(KIND, {
            params: { url: ARDOT_ORIGIN + "/" }
          })
        }, "◆"))
      ), "ardot-panel: header trigger");
    }

    exports.apply  = apply;
    exports.inject = inject;
    exports.name   = name;
    return module.exports;
  }
});
```

#### 需要注册的 API 一览

| 注册项 | API | 必填 | 说明 |
|---|---|---|---|
| tab 类型 | `ctx.sidebarRightTabs.register({ id, kind, title, ... })` | `id`、`kind`、`title` | 不写 `priority` 即 `extension`（最高档）；`multiple:true` 可多实例；`guide[]` 决定是否进引导页 |
| tab 主体 | `ctx.slots.register({ name: 'sidebar.right.pane.tab', key: <id> }, Component)` | `name`、`key` | body 用 `useTabInfo()` 拿 `tab.navigation.params` |
| tab 标题 | `ctx.slots.register({ name: 'sidebar.right.pane.tab.title', key: <id> }, Component)` | `name`、`key` | 不注册就用 open 时抓的 title |
| 打开 tab | `ctx.sidebarRight.openTab(kind, { params })` | — | 必须在有 on-screen session 时调，否则抛 `no session surface is mounted`（`client.js:2594-2602`） |
| （可选）菜单项 | `ctx.slots.register({ name: 'sidebar.right.tab.menu.item', ... })` | list → `id` | 追加到 tab 右键菜单 |
| （可选）guide 卡片 | 类型声明里的 `guide: [{ id, order, title, description, icon }]` | `id` | `order` 越小越靠前；`icon` 可省（省则用 guide 的 cube 占位，`EntryBox` 里 `entry.icon ?? CubeGlyph`，`ui-sidebar-right/lib/client.js:518`） |
| （可选）对话 header 按钮 | `ctx.slots.register({ name: 'conversation.session.header.actions', id, order, label }, C)` | list/session → `id` | 声明在 `ui-conversation/lib/client.js:20115-20118`（`kind:"list", scope:"session"`），渲染点 `:18272` |

#### 接线步骤

```bash
# 1) 建目录、写文件（如上）

# 2) 把包 link 进 profile
cd C:\Users\bx233\.dsh\profiles\desktop
# 编辑 package.json：
#   dependencies 里加  "@local/dsh-ardot-panel": "link:C:/Users/bx233/Documents/deepseek-harness/default-workspace/dsh-ardot-panel"
#   dsh.profile.bundles 里加  "@local/dsh-ardot-panel"

# 3) 让 pnpm 建立 node_modules 链接（desktop profile 有 pnpm-workspace.yaml）
pnpm install

# 4) 重启 DSH（或依赖 client-hmr 的 500ms poll 自动换 client bundle）
```

> 这里的「编辑 `~/.dsh/profiles/desktop/package.json`」是**改你的 profile，不是改安装目录**，符合「安装目录只读」约束。
> 若想完全不碰 `package.json`，也可只把 `- insert: [{id, name}]` 写进 `~/.dsh/cordis.patch.yml`（home 层，优先级最高），但那样包仍需能被 Node 解析到 —— 所以 `link:` 到 profile 的 `package.json` 是最稳的。

### 7.2 验证顺序（建议按此逐步推进，每步都能止损）

| 步 | 动作 | 预期 | 失败含义 |
|---|---|---|---|
| 0 | 右侧栏 → 引导页 → **浏览器** → 输入 `https://ardot.tencent.com/` | Ardot 正常显示 | 若白屏：Ardot 有反嵌（本次实测无）/ 网络 / Ardot 自身 CSP。**先卡在这一步就别写代码** |
| 1 | 若 step 0 通，写成最小插件：只有 `guide` + body（**固定 URL，无 postMessage**） | 引导页多一个 "Ardot 画布" 卡片，点开是画布 | 说明注册链路有问题，对照 §4.3 检查 `exports["./client"]`、`bundle.patch`、`bundles` 列表 |
| 2 | 加 `conversation.session.header.actions` 按钮 → `openTab(KIND, {params:{url}})`,验证 `params.url` 生效 | 点按钮开新 tab 到指定画布 | `params` 没透传 → 检查 `tab.navigation.params` 是否被 `place()` 写入（`client.js:2309-2320`） |
| 3 | 加 postMessage 双向 | Ardot 主动 `postMessage` 到父窗口时能收到 | 收不到 ⇒ **Ardot 端没有发消息的代码**，此时确认「iframe 桥接对 Ardot 不可用」，转 §6.3 的务实建议 |
| 4 | （可选）用 `sandbox` 去掉 `allow-same-origin` 收紧 | 若 Ardot 不需要 cookie，安全性更好 | 画布功能异常 ⇒ 恢复 sandbox 串 |

### 7.3 方案 A 的一行验证（现在就可用）

不用写插件，直接在任意已注册到 `ctx` 的插件里调：
```js
ctx.sidebarRight.openTab('browser', { params: { url: 'https://ardot.tencent.com/file/693499159567438' } });
```
或最简单：右侧栏引导页点「浏览器」，地址栏输入 Ardot URL。

---

## 8. 附录

### 8.1 关键文件索引

| 主题 | 文件 | 关键行 |
|---|---|---|
| root slot 声明（6 个子 slot） | `@deepseek-ai/dsh-client-ui-layout/lib/client.js` | 623-653 |
| `ctx.layout` API | 同上 | 452-495 |
| `ctx.slots` 服务安装 | `@deepseek-ai/dsh-client-ui-renderer/lib/client.js` | 1305-1352, 1867-1875 |
| slot 渲染 props 合并 | 同上 | 792-810 |
| `rightbar` / `rightbar.session` / `sidebar.right.*` 声明 | `@deepseek-ai/dsh-client-ui-sidebar-right/lib/client.js` | 5030-5126 |
| tab 类型注册表 + band | 同上 | 4508-4522, 4580-4611, 4639-4740 |
| `ctx.sidebarRight` / `sidebarRightTabs` provide | 同上 | 4965-4966 |
| `openTab` / `openResource` | 同上 | 2234-2246, 2594-2602 |
| `TabSlot`（body 容器 + `useTabInfo`） | 同上 | 1368-1404, 4856-4892 |
| tab 注册两段式文档 | `@deepseek-ai/dsh-client-ui-sidebar-right/README.md` | 81-88 |
| iframe carrier + sandbox 常量 | `@deepseek-ai/dsh-client-ui-sidebar-browser/lib/client.js` | 919-985 |
| webview carrier | 同上 | 1335-1344 |
| 运行时 carrier 分流 | 同上 | 1596-1633 |
| URL 策略 | 同上 | 363-407 |
| `browserDefinition` | 同上 | 1434-1450 |
| `dsh.client` 校验 | `@deepseek-ai/dsh-client-modules/lib/index.js` | 56-76, 172-182, 733-748 |
| bundle 注册校验 | `@deepseek-ai/dsh-client-modules/lib/client.js` | 758, 874 |
| HMR poll + replace | `@deepseek-ai/dsh-client-hmr/lib/index.js` / `dsh-client-modules/lib/client.js` | 22, 46-63 / 492-505 |
| `ctx.remote.$mount` | `@deepseek-ai/dsh-api-gateway/lib/client.browser.js` | 1669 |
| webview 主进程审批 | `resources/app/lib/main.js` | 10592-10625, 10636-10704 |
| `window.dshDesktop` 注入 | `resources/app/lib/preload-app.cjs` | 757-785, 789-830, 876 |
| 平台静态模块表 | `dsh-web-frontend/dist/assets/index-BXV-oxvX.js` | `OZ()` 函数 |
| shipped row（含条件 disable） | `@deepseek-ai/dsh-web-app/cordis.patch.yml` | 295-297 |

### 8.2 「未找到」清单（明确无证据，不编造）

以下 API/能力在安装包的**全部** `lib/*.js` 与 shell bundle 中 **grep 零命中**，本报告不为其编造接口：

1. `ctx.layout.registerPanel(...)` / `ctx.layout.register*(...)` —— **不存在**。`ctx.layout` 只有 6 个成员（见 §1.2）。
2. `sidebar-browser` 的 `postMessage` / `MessageChannel` / `executeJavaScript` / `insertCSS` / 脚本注入 API —— **不存在**。
3. 右侧栏的域名白名单 / URL 黑白名单配置 —— **不存在**（只有协议级 allowlist）。
4. 客户端插件新增 Electron IPC channel 的扩展点 —— **不存在**（preload 面固定）。
5. `@deepseek-ai/dsh-client-ui-sidebar-*` 系列的 `.d.ts` 类型声明 —— **包里没有**（`files` 字段声称会发 `lib/types/**/*.d.ts`，实际安装包未带）。所以第三方只能靠 README + bundle 反推 API 形状。
6. `~/.dsh/plugins/` 作为插件代码加载路径 —— **不是**，那是运行期数据目录。
7. `dsh-client-ui-slots` / `dsh-client-ui-dockkit` / `dsh-client-store` / `dsh-client-ui-primitives` 的源码或 `.d.ts` —— **磁盘上没有**，只在 shell 静态模块表里（见 §1.1）。

### 8.3 三条给决策者的实话

1. **「右侧栏嵌 Ardot」技术上零障碍**：Ardot 无 X-Frame-Options（实测），DSH 无 CSP 拦截，sidebar-browser 已把 iframe 路走通。最快验证是引导页 → 浏览器 → 粘 URL。
2. **但「iframe 桥接 = 双向控制 Ardot 画布」不成立**：sidebar-browser 不暴露任何页面内通道；就算你自建 iframe，也必须有 Ardot 端配合 `postMessage` 才有双向。**要 agent 操作画布，走 MCP（你已有 `@local/ardot-mcp`），别指望 UI 层桥接。**
3. **改安装目录是死路**：`main.js` / `preload-app.cjs` 的 IPC 白名单是硬编码 + 严格 origin/frame 校验，改了升级即失效。所有扩展都该落在 `~/.dsh/profiles/desktop/` 这一层。
